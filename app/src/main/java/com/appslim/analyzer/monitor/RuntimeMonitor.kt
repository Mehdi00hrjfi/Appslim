package com.appslim.analyzer.monitor

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.util.Calendar

/**
 * 运行时监控（报告 §5 / §13 关键代码示例）
 *
 * - 运行进程：ActivityManager.runningAppProcesses（报告 §13.1）
 * - 使用事件：UsageStatsManager（Activity 启动次数、学习模式数据源，报告 §12.1）
 */
class RuntimeMonitor(private val context: Context) {

    data class RunningProcess(val pid: Int, val processName: String, val importance: Int)

    /** 报告 §13.1：获取运行进程 */
    fun runningProcesses(): List<RunningProcess> {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.runningAppProcesses?.map {
            RunningProcess(it.pid, it.processName, it.importance)
        } ?: emptyList()
    }

    /** 目标应用的全部 pid */
    fun pidsOf(packageName: String): List<Int> =
        runningProcesses().filter { it.processName.startsWith(packageName) }.map { it.pid }

    /**
     * 学习模式数据源（报告 §12.1）：统计 [days] 天内目标应用的前台事件，
     * 输出「用户使用 N 次 / 从不打开某模块」的判断依据。
     */
    fun usageEvents(packageName: String, days: Int = 7): Int {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val end = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -days) }
        val events = usm.queryEvents(cal.timeInMillis, end)
        val e = UsageEvents.Event()
        var launches = 0
        while (events.getNextEvent(e)) {
            if (e.packageName == packageName &&
                e.eventType == UsageEvents.Event.ACTIVITY_RESUMED
            ) launches++
        }
        return launches
    }

    /** 本进程主动让出（工具方法占位：权限检测逻辑见 MainViewModel） */
    fun selfPid(): Int = Process.myPid()
}
