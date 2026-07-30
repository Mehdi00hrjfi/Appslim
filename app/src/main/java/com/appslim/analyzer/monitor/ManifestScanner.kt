package com.appslim.analyzer.monitor

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import com.appslim.analyzer.data.model.ComponentType

/**
 * APK 静态扫描（报告 §15 第一阶段：APK扫描 / Manifest分析 / 组件列表）
 */
class ManifestScanner(private val context: Context) {

    data class StaticComponent(val className: String, val type: ComponentType, val exported: Boolean)

    data class AppManifest(
        val packageName: String,
        val label: String,
        val versionName: String,
        val components: List<StaticComponent>
    ) {
        fun byType(type: ComponentType) = components.filter { it.type == type }
    }

    /** 扫描单个应用的 Manifest，列出四大组件 */
    fun scan(packageName: String): AppManifest? {
        val pm = context.packageManager
        val flags = PackageManager.GET_ACTIVITIES or PackageManager.GET_SERVICES or
                PackageManager.GET_RECEIVERS or PackageManager.GET_PROVIDERS
        val info: PackageInfo = try {
            if (Build.VERSION.SDK_INT >= 33)
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            else
                @Suppress("DEPRECATION") pm.getPackageInfo(packageName, flags)
        } catch (e: PackageManager.NameNotFoundException) {
            return null
        }

        val comps = mutableListOf<StaticComponent>()
        info.activities?.forEach { comps += StaticComponent(it.name, ComponentType.ACTIVITY, it.exported) }
        info.services?.forEach { comps += StaticComponent(it.name, ComponentType.SERVICE, it.exported) }
        info.receivers?.forEach { comps += StaticComponent(it.name, ComponentType.RECEIVER, it.exported) }
        info.providers?.forEach { comps += StaticComponent(it.name, ComponentType.PROVIDER, it.exported) }

        val appInfo = info.applicationInfo
        return AppManifest(
            packageName = packageName,
            label = appInfo?.let { pm.getApplicationLabel(it).toString() } ?: packageName,
            versionName = info.versionName ?: "-",
            components = comps
        )
    }

    /** 列出全部已安装应用（供应用列表页） */
    fun installedPackages(): List<String> =
        context.packageManager.getInstalledPackages(0)
            .map { it.packageName }
            .filter { it != context.packageName }
}
