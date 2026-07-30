package com.appslim.analyzer.lens.scanner

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * 应用信息扫描（v0.5.0 §5 Manifest 扫描）
 * 仅做基础信息提取，不做组件分析（组件见 ActivityEnumerator）。
 */
class PackageScanner(private val pm: PackageManager) {

    data class AppInfo(
        val packageName: String,
        val label: String,
        val versionName: String,
        val isSystem: Boolean,
        val sourceDir: String,
        val iconPresent: Boolean
    )

    fun scan(packageName: String): AppInfo? = runCatching {
        val ai = pm.getApplicationInfo(packageName, 0) ?: return@runCatching null
        AppInfo(
            packageName = packageName,
            label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrDefault(packageName),
            versionName = runCatching {
                if (Build.VERSION.SDK_INT >= 33) {
                    pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L)).versionName
                } else {
                    @Suppress("DEPRECATION") pm.getPackageInfo(packageName, 0).versionName
                }
            }.getOrNull() ?: "-",
            isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
            sourceDir = ai.sourceDir,
            iconPresent = ai.icon != 0
        )
    }.getOrNull()
}
