package com.appslim.analyzer.monitor

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.appslim.analyzer.data.model.ComponentType

/**
 * APK 静态扫描（报告 §15 第一阶段：APK扫描 / Manifest分析 / 组件列表）
 *
 * 【这一版扩了什么】
 * 原来每个组件只存 className / type / exported 三个字段，够列表展示，
 * 但不够做判断 —— 「这个组件冻了会怎样」的答案全在丢掉的那些字段里：
 *
 *   - foregroundServiceType：非 0 = 前台服务，冻了要么崩要么功能直接没
 *   - permission：为空 + exported = 谁都能拉起来，典型的被薅电组件
 *   - grantUriPermissions：Provider 授 URI 权限，可能是数据泄露面
 *   - processName：独立进程的组件，冻掉省的是整个进程的内存
 *   - 匹配到的 intent action：BOOT_COMPLETED 之类 = 自启入口
 *
 * 参考 Blocker（lihenggui/blocker）的做法。它靠这几个字段做出了不依赖
 * 「SDK 名单」的行为分类 —— 名单永远追不上新 SDK，行为特征却是稳定的。
 *
 * 【intent-filter 怎么拿到的】
 * PackageManager 不直接给 intent-filter，硬解 APK 的 binary XML 又太重。
 * 这里换个思路：拿一组「探针 Intent」去 query，反查哪些组件能响应。
 * 覆盖不了全部 filter，但自启、深链、推送这几类关键行为都能识别出来，
 * 成本只有十几次 PM 查询。
 */
class ManifestScanner(private val context: Context) {

    data class StaticComponent(
        val className: String,
        val type: ComponentType,
        val exported: Boolean,
        /** 访问所需权限，null = 无保护 */
        val permission: String? = null,
        /** 仅 Service：Android 10+ 的前台服务类型，非 0 表示声明过前台服务 */
        val foregroundServiceType: Int = 0,
        /** 仅 Provider：是否授予临时 URI 权限 */
        val grantUriPermissions: Boolean = false,
        /** 运行所在进程；与包名不同则是独立进程 */
        val processName: String? = null,
        /** Manifest 里 android:enabled 的声明值 */
        val enabledByDefault: Boolean = true,
        /** 直接启动感知，这类组件在解锁前就能跑 */
        val directBootAware: Boolean = false,
        /** 探针命中的 intent action 集合 */
        val matchedActions: Set<String> = emptySet(),
    ) {
        val isForegroundService: Boolean get() = foregroundServiceType != 0
        val isIsolatedProcess: Boolean
            get() = processName != null && processName.contains(':')
    }

    data class AppManifest(
        val packageName: String,
        val label: String,
        val versionName: String,
        val components: List<StaticComponent>,
        /** App 声明的权限，判定 WAKE_LOCK / 精确闹钟这类行为要用 */
        val permissions: Set<String> = emptySet(),
    ) {
        fun byType(type: ComponentType) = components.filter { it.type == type }
    }

    /** 扫描单个应用的 Manifest，列出四大组件 */
    fun scan(packageName: String): AppManifest? {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS or
                PackageManager.GET_PERMISSIONS or DISABLED_FLAG
        val info: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= 33)
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else
                @Suppress("DEPRECATION") pm.getPackageInfo(packageName, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }

        // 先把探针结果算出来，建组件时直接查
        val actionMap = probeIntentFilters(packageName)

        val comps = mutableListOf<StaticComponent>()
        info.activities?.forEach {
            comps += StaticComponent(
                className = it.name,
                type = ComponentType.ACTIVITY,
                exported = it.exported,
                permission = it.permission,
                processName = it.processName,
                enabledByDefault = it.enabled,
                directBootAware = it.directBootAwareCompat(),
                matchedActions = actionMap[it.name].orEmpty(),
            )
        }
        info.services?.forEach {
            comps += StaticComponent(
                className = it.name,
                type = ComponentType.SERVICE,
                exported = it.exported,
                permission = it.permission,
                foregroundServiceType = runCatching {
                    if (Build.VERSION.SDK_INT >= 29) it.foregroundServiceType else 0
                }.getOrDefault(0),
                processName = it.processName,
                enabledByDefault = it.enabled,
                directBootAware = it.directBootAwareCompat(),
                matchedActions = actionMap[it.name].orEmpty(),
            )
        }
        info.receivers?.forEach {
            comps += StaticComponent(
                className = it.name,
                type = ComponentType.RECEIVER,
                exported = it.exported,
                permission = it.permission,
                processName = it.processName,
                enabledByDefault = it.enabled,
                directBootAware = it.directBootAwareCompat(),
                matchedActions = actionMap[it.name].orEmpty(),
            )
        }
        info.providers?.forEach {
            comps += StaticComponent(
                className = it.name,
                type = ComponentType.PROVIDER,
                exported = it.exported,
                // Provider 的读写权限是分开的，取任意一个非空即可视为有保护
                permission = it.readPermission ?: it.writePermission,
                grantUriPermissions = it.grantUriPermissions,
                processName = it.processName,
                enabledByDefault = it.enabled,
                directBootAware = it.directBootAwareCompat(),
            )
        }

        val appInfo = info.applicationInfo
        return AppManifest(
            packageName = packageName,
            label = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: packageName,
            versionName = info.versionName ?: "-",
            components = comps,
            permissions = info.requestedPermissions?.toSet() ?: emptySet(),
        )
    }

    /**
     * 用探针 Intent 反查组件的 intent-filter。
     *
     * 每个探针查一次 PM，把命中的组件记下来。返回 类名 → 命中的 action 集合。
     * 注意要带 MATCH_DISABLED_COMPONENTS，否则已经被冻的组件查不出来 ——
     * 而恰恰是这些组件，用户最需要知道「它原来是干什么的」。
     */
    private fun probeIntentFilters(packageName: String): Map<String, MutableSet<String>> {
        val pm = context.packageManager
        val result = HashMap<String, MutableSet<String>>()

        fun record(name: String?, action: String) {
            if (name.isNullOrEmpty()) return
            result.getOrPut(name) { mutableSetOf() }.add(action)
        }

        BROADCAST_PROBES.forEach { action ->
            runCatching {
                val intent = Intent(action).setPackage(packageName)
                @Suppress("DEPRECATION")
                pm.queryBroadcastReceivers(intent, DISABLED_FLAG)
                    .forEach { record(it.activityInfo?.name, action) }
            }
        }
        SERVICE_PROBES.forEach { action ->
            runCatching {
                val intent = Intent(action).setPackage(packageName)
                @Suppress("DEPRECATION")
                pm.queryIntentServices(intent, DISABLED_FLAG)
                    .forEach { record(it.serviceInfo?.name, action) }
            }
        }

        // 深链：VIEW + BROWSABLE + http(s)，能被浏览器直接唤起的 Activity
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW)
                .addCategory(Intent.CATEGORY_BROWSABLE)
                .setPackage(packageName)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, DISABLED_FLAG)
                .forEach { record(it.activityInfo?.name, ACTION_DEEPLINK) }
        }

        // 桌面图标入口，这类 Activity 冻了图标就没了，必须标出来
        runCatching {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage(packageName)
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, DISABLED_FLAG)
                .forEach { record(it.activityInfo?.name, ACTION_LAUNCHER) }
        }

        return result
    }

    /** 列出全部已安装应用（供应用列表页） */
    fun installedPackages(): List<String> =
        context.packageManager.getInstalledPackages(0)
            .map { it.packageName }
            .filter { it != context.packageName }

    private fun android.content.pm.ComponentInfo.directBootAwareCompat(): Boolean =
        runCatching {
            if (Build.VERSION.SDK_INT >= 24) directBootAware else false
        }.getOrDefault(false)

    companion object {
        private const val DISABLED_FLAG = PackageManager.MATCH_DISABLED_COMPONENTS

        /** 深链和桌面入口不是真实 action，用伪 action 名标记，方便统一处理 */
        const val ACTION_DEEPLINK = "@deeplink"
        const val ACTION_LAUNCHER = "@launcher"

        /**
         * 广播探针。挑的都是「会导致组件在用户没打开 App 时被唤醒」的系统事件 ——
         * 这正是后台耗电的主要来源。
         */
        val BROADCAST_PROBES = listOf(
            Intent.ACTION_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_PACKAGE_ADDED,
            Intent.ACTION_PACKAGE_REPLACED,
            Intent.ACTION_USER_PRESENT,
            Intent.ACTION_SCREEN_ON,
            "android.net.conn.CONNECTIVITY_CHANGE",
            "android.net.wifi.STATE_CHANGE",
            Intent.ACTION_POWER_CONNECTED,
            Intent.ACTION_BATTERY_CHANGED,
            Intent.ACTION_MEDIA_MOUNTED,
            Intent.ACTION_TIME_TICK,
            Intent.ACTION_LOCALE_CHANGED,
        )

        /** 服务探针。第三方推送 SDK 基本都靠这几个 action 被系统或友商 App 拉活 */
        val SERVICE_PROBES = listOf(
            "com.google.firebase.MESSAGING_EVENT",
            "com.google.android.c2dm.intent.RECEIVE",
            "com.huawei.push.action.MESSAGING_EVENT",
            "com.heytap.mcs.action.RECEIVE_MCS_MESSAGE",
            "com.vivo.pushservice.action.RECEIVE",
            "com.xiaomi.mipush.RECEIVE_MESSAGE",
            "android.intent.action.RESPOND_VIA_MESSAGE",
        )
    }
}
