package com.appslim.analyzer.lens.collector

import com.appslim.analyzer.lens.PermissionLevel
import com.appslim.analyzer.lens.util.Shell
import java.io.File

/**
 * CPU 采集（v0.5.0 §7）
 * 优先级：Root → /proc/[pid]/stat；ADB → dumpsys cpuinfo；普通 → 尝试 /proc/[pid]/stat。
 *
 * 单次采样返回原始 jiffies 与（ADB 模式下的）直接百分比；
 * 百分比差值由调用方按采样间隔计算，避免依赖内部状态。
 */
object CpuCollector {

    data class CpuSample(val jiffies: Long, val directPct: Float?)

    private val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    private val clkTck: Float = runCatching {
        android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK).toFloat().coerceAtLeast(1f)
    }.getOrDefault(100f)

    /** 读取 /proc/[pid]/stat 的 utime+stime（jiffies），失败返回 null */
    fun readProcJiffies(pid: Int): Long? = runCatching {
        val line = File("/proc/$pid/stat").readText()
        val after = line.substringAfter(") ")
        val parts = after.split(" ")
        // 1-based: utime=14, stime=15 → 0-based 13,14
        (parts[13].toLong() + parts[14].toLong())
    }.getOrNull()

    /** 从 dumpsys cpuinfo 解析目标 pid 的占用百分比（ADB 模式） */
    fun readDumpsysCpu(pid: Int): Float? {
        val out = Shell.exec("dumpsys cpuinfo")
        if (out.isBlank()) return null
        // 形如： 12% 12345/com.demo: 8% user + 4% kernel
        val regex = Regex("""(\d+)%\s+$pid/""")
        return regex.find(out)?.groupValues?.get(1)?.toFloatOrNull()
    }

    fun sample(pid: Int, level: PermissionLevel): CpuSample {
        return when (level) {
            PermissionLevel.ROOT -> {
                val j = readProcJiffies(pid)
                CpuSample(j ?: 0L, null)
            }
            PermissionLevel.ADB -> {
                val pct = readDumpsysCpu(pid)
                CpuSample(0L, pct)
            }
            PermissionLevel.NORMAL -> {
                val j = readProcJiffies(pid)
                CpuSample(j ?: 0L, null)
            }
        }
    }

    /** 由两次 jiffies 采样计算 CPU 百分比（按核心数归一化） */
    fun deltaPct(prevJiffies: Long, currJiffies: Long, intervalMs: Long): Float {
        val diff = currJiffies - prevJiffies
        if (diff <= 0 || intervalMs <= 0) return 0f
        return diff * 100_000f / (clkTck * intervalMs * coreCount)
    }
}
