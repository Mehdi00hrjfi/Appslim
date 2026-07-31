package com.appslim.analyzer.data.repo

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import com.appslim.analyzer.data.db.AppDatabase
import com.appslim.analyzer.data.db.AppInfoEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用列表数据层（MT Manager 式架构）：
 *   PackageManager（快列表） + Room 缓存（避免重复扫描） + 后台轻量富化（仅新装/更新项）
 *
 * 三层职责：
 *   1) [fastList]   —— getInstalledApplications(0)，100–300ms 出列表，不做组件扫描、不解码图标；
 *   2) [cachedAll]  —— 从 Room 一次性读全表缓存，命中即免扫描；
 *   3) [enrich]     —— 仅对「缓存缺失 / APK 变化」的应用做一次轻量 PackageInfo 扫描，写回 Room。
 *
 * 注意：深度分析（ManifestScanner 全量 intent-filter 探针 + 行为分类）仍只在用户
 * 点击某应用（selectApp）时触发，绝不在列表阶段做——这是这套架构「快」的根本。
 */
class AppRepository(private val app: Context) {

    private val pm get() = app.packageManager
    private val dao get() = AppDatabase.get(app).appCacheDao()

    /** 快列表单元：只带展示与定位所需的最小信息 */
    data class FastApp(
        val pkg: String,
        val label: String,
        val apkPath: String,
        val isSystem: Boolean
    )

    /** 富化后的元数据：版本、安装/更新时间、四大组件计数 */
    data class Meta(
        val pkg: String,
        val version: String,
        val isSystem: Boolean,
        val apkPath: String,
        val activityCount: Int,
        val serviceCount: Int,
        val receiverCount: Int,
        val providerCount: Int,
        val installTime: Long,
        val updateTime: Long
    ) {
        val compCount: Int get() = activityCount + serviceCount + receiverCount + providerCount
        /** 后台组件（服务 + 接收器）＝ 最该被瘦身、最常偷跑的指标 */
        val backgroundCount: Int get() = serviceCount + receiverCount
    }

    /**
     * 快列表：零额外 flag，只取包名 / 显示名 / 源路径 / 系统标志。
     * 不碰 APK 内的组件、不解码图标，200 个应用通常 100–300ms 完成。
     */
    suspend fun fastList(): List<FastApp> = withContext(Dispatchers.IO) {
        pm.getInstalledApplications(0)
            .filter { it.packageName != app.packageName }
            .map { ai ->
                FastApp(
                    pkg = ai.packageName,
                    label = runCatching { pm.getApplicationLabel(ai).toString() }
                        .getOrNull() ?: ai.packageName,
                    apkPath = ai.sourceDir ?: "",
                    isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            }
    }

    /** 批量读缓存（一次查询全表，避免逐包 IO） */
    suspend fun cachedAll(): Map<String, AppInfoEntity> = withContext(Dispatchers.IO) {
        dao.all().associateBy { it.packageName }
    }

    /**
     * 轻量富化：拿版本、安装/更新时间、四大组件计数。
     * 若缓存命中且 APK 未变（apkPath 一致）直接复用，省掉一次 PackageInfo；
     * 否则做一次 GET_ACTIVITIES|SERVICES|RECEIVERS|PROVIDERS 扫描并持久化。
     */
    suspend fun enrich(fast: FastApp): Meta = withContext(Dispatchers.IO) {
        val cached = runCatching { dao.get(fast.pkg) }.getOrNull()
        if (cached != null && cached.apkPath == fast.apkPath && cached.versionName.isNotEmpty()) {
            return@withContext Meta(
                pkg = fast.pkg,
                version = cached.versionName,
                isSystem = fast.isSystem,
                apkPath = fast.apkPath,
                activityCount = cached.activityCount,
                serviceCount = cached.serviceCount,
                receiverCount = cached.receiverCount,
                providerCount = cached.providerCount,
                installTime = cached.installTime,
                updateTime = cached.updateTime
            )
        }

        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= 33)
                pm.getPackageInfo(fast.pkg, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else
                @Suppress("DEPRECATION") pm.getPackageInfo(fast.pkg, flags)
        }.getOrNull()

        val meta = if (info != null) {
            Meta(
                pkg = fast.pkg,
                version = info.versionName ?: "-",
                isSystem = fast.isSystem,
                apkPath = fast.apkPath,
                activityCount = info.activities?.size ?: 0,
                serviceCount = info.services?.size ?: 0,
                receiverCount = info.receivers?.size ?: 0,
                providerCount = info.providers?.size ?: 0,
                installTime = info.firstInstallTime,
                updateTime = info.lastUpdateTime
            )
        } else {
            Meta(fast.pkg, "-", fast.isSystem, fast.apkPath, 0, 0, 0, 0, 0, 0)
        }

        runCatching {
            dao.put(
                AppInfoEntity(
                    packageName = meta.pkg,
                    appName = fast.label,
                    versionName = meta.version,
                    apkPath = meta.apkPath,
                    isSystem = meta.isSystem,
                    activityCount = meta.activityCount,
                    serviceCount = meta.serviceCount,
                    receiverCount = meta.receiverCount,
                    providerCount = meta.providerCount,
                    installTime = meta.installTime,
                    updateTime = meta.updateTime,
                    cachedAt = System.currentTimeMillis()
                )
            )
        }
        meta
    }

    /** 删除已卸载应用的缓存行 */
    suspend fun prune(installedPkgs: List<String>) = withContext(Dispatchers.IO) {
        runCatching { dao.prune(installedPkgs) }
    }
}
