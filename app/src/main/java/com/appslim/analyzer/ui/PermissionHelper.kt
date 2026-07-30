package com.appslim.analyzer.ui

import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.appslim.analyzer.root.RootShell

/**
 * 权限助手：应用列表权限（使用情况访问）+ Root 能力检测
 */
object PermissionHelper {

    /** 应用列表权限：「使用情况访问（Package Usage Stats）」是否已授权 */
    fun hasUsageAccess(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    /** 跳转系统「使用情况访问」设置页（报告 §5 数据采集入口） */
    fun openUsageSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }

    /** 是否存在 Root（Magisk / KernelSU / APatch） */
    fun hasRoot(): Boolean = RootShell.isRootAvailable
}
