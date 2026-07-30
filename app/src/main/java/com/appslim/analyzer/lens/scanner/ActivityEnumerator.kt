package com.appslim.analyzer.lens.scanner

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.os.Build

/**
 * Activity 枚举（v0.5.0 §5）
 * 通过 PackageManager.GET_ACTIVITIES 列出目标包全部 Activity，
 * 标注 exported 与是否为 launcher 入口。
 */
class ActivityEnumerator(private val pm: PackageManager) {

    data class ActivityInfo(
        val packageName: String,
        val className: String,
        val exported: Boolean,
        val launcher: Boolean
    )

    fun enumerate(packageName: String): List<ActivityInfo> {
        val activities = runCatching {
            val flags = PackageManager.GET_ACTIVITIES
            val info = if (Build.VERSION.SDK_INT >= 33) {
                pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(flags.toLong()))
            } else {
                @Suppress("DEPRECATION") pm.getPackageInfo(packageName, flags)
            }
            info.activities?.toList().orEmpty()
        }.getOrElse { emptyList() }

        val launcherNames = launcherActivities(packageName)
        return activities.map { ai: android.content.pm.ActivityInfo ->
            ActivityInfo(
                packageName = packageName,
                className = ai.name,
                exported = ai.exported,
                launcher = ai.name in launcherNames
            )
        }
    }

    /** 通过 CATEGORY_LAUNCHER 解析出 launcher Activity 名称集合 */
    private fun launcherActivities(packageName: String): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return runCatching {
            pm.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo }
                .filter { it.packageName == packageName }
                .map { it.name }
                .toSet()
        }.getOrDefault(emptySet())
    }
}
