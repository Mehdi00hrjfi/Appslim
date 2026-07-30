package com.appslim.analyzer.lens.launcher

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.io.File

/**
 * 前台 Activity 探测器（v0.5.5 引入，参考 APPLENS 思路）
 *
 * 核心：优先用 [UsageStatsManager.queryEvents] 读取「最近 3s 内进入前台的 Activity」。
 * 该接口在**普通模式（无需 Root/ADB）**即可使用，仅需用户授予「使用情况访问」权限
 * （Manifest 已声明 PACKAGE_USAGE_STATS，PermissionHelper 负责引导开启）。
 *
 * 与普通模式去读 `dumpsys activity activities`（跨 UID 受限、普通模式拿不到）相比，
 * UsageStats 是权限模型、不挑 UID，因此普通模式下也能稳定识别「目标 App 当前是哪个 Activity」。
 *
 * 兜底：若 UsageStats 无权限/为空，则退化为扫描 /proc 判断目标进程是否存活（只能确认「在跑」，
 * 拿不到具体 Activity 类名），返回 null 由上层提示开启权限。
 */
class ActivityDetector(private val context: Context) {

    /** 是否拥有「使用情况访问」权限 */
    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /**
     * 取目标包当前前台 Activity 的完整类名（如 com.foo/.MainActivity）。
     * 取不到（无权限 / 目标不在前台）返回 null。
     */
    fun detectForeground(targetPackage: String): String? = runCatching {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 3000, now)
        val e = UsageEvents.Event()
        var cls: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                e.packageName == targetPackage
            ) {
                cls = e.className
            }
        }
        cls
    }.getOrNull() ?: fallbackProcAlive(targetPackage)

    /**
     * 兜底：普通模式无 UsageStats 权限时，无法拿到具体的 Activity 类名，
     * 只能确认目标进程是否存活。这里直接返回 null——宁可 Activity 列表为空并提示去开权限，
     * 也不要把包名伪装成一个 Activity 显示出来。
     */
    private fun fallbackProcAlive(targetPackage: String): String? = null
}
