package com.appslim.analyzer.lens.collector

import android.app.ActivityManager
import com.appslim.analyzer.lens.PermissionLevel
import com.appslim.analyzer.lens.util.Shell
import java.io.File
import kotlin.math.max

/**
 * 内存采集（v0.5.0 §8）
 * Root → /proc/[pid]/status (VmRSS)；ADB → dumpsys meminfo（TOTAL PSS）；
 * 普通 → ActivityManager.getProcessMemoryInfo（仅本应用可靠）。
 * 返回 MB。
 */
object MemoryCollector {

    fun sample(pid: Int, packageName: String, level: PermissionLevel, am: ActivityManager): Int {
        return when (level) {
            PermissionLevel.ROOT -> readProcRss(pid)
            PermissionLevel.ADB -> parseDumpsysMem(packageName)
            PermissionLevel.NORMAL -> {
                val m = runCatching {
                    am.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull()?.totalPss ?: 0
                }.getOrDefault(0)
                max(m / 1024, 0)
            }
        }
    }

    private fun readProcRss(pid: Int): Int {
        val status = File("/proc/$pid/status").readText()
        val line = status.lineSequence().firstOrNull { it.startsWith("VmRSS:") } ?: return 0
        // VmRSS:   12345 kB
        val kb = line.split(Regex("\\s+")).getOrNull(1)?.toIntOrNull() ?: return 0
        return kb / 1024
    }

    private fun parseDumpsysMem(packageName: String): Int {
        val out = Shell.exec("dumpsys meminfo $packageName")
        if (out.isBlank()) return 0
        // 形如： TOTAL    12345    ...
        val regex = Regex("""TOTAL\s+(\d+)""")
        return regex.find(out)?.groupValues?.get(1)?.toIntOrNull()?.div(1024) ?: 0
    }
}
