package com.appslim.analyzer.monitor

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.system.Os
import android.system.OsConstants
import com.appslim.analyzer.root.RootShell
import com.appslim.analyzer.ui.toBitmap
import kotlinx.coroutines.delay
import java.io.File

/**
 * 真实进程采样器：
 * - Root 可用时通过 su 读 /proc，获取全部进程（含系统进程）
 * - 无 Root 时回退 ActivityManager.getRunningAppProcesses()（Android 10+ 仅可见自身）
 * - 读取 /proc/<pid>/stat 计算 CPU 占用
 * - 读取 PSS 估算内存
 * - Root 下可 dumpsys 查看 Activity/Service，并可 force-stop
 *
 * 关键设计：CPU 用「单次快照 + 与上一次快照的滴答差」计算，
 * 首帧 CPU=0 立即上屏，下一帧补齐，无需内部 delay 阻塞 UI。
 */
class ProcessSampler(private val context: Context) {

    data class RawProcess(
        val pid: Int,
        val pkg: String,
        val label: String,
        val importance: Int,
        val icon: Bitmap? = null,
        val cpuPercent: Float = 0f,
        val memoryMb: Int = 0
    )

    private val pm = context.packageManager
    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /** 逻辑 CPU 核心数 */
    private val coreCount = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)

    /** Linux 用户态时钟频率（通常 100，即 10ms/滴答） */
    private val clkTck = runCatching {
        Os.sysconf(OsConstants._SC_CLK_TCK).toFloat().coerceAtLeast(1f)
    }.getOrDefault(100f)

    /**
     * 计算 CPU 占用百分比（按总 CPU 能力归一化）。
     * 公式：(滴答差 / clkTck) / (intervalMs/1000) / coreCount * 100
     * 8 核手机上单进程吃满 1 核约为 12.5%，吃满全部 8 核为 100%。
     */
    private fun calcCpuPercent(jiffiesDiff: Long, intervalMs: Long): Float {
        if (jiffiesDiff <= 0 || intervalMs <= 0) return 0f
        return jiffiesDiff * 100_000f / (clkTck * intervalMs * coreCount)
    }

    /** 读取 /proc/stat 第一行，返回总 CPU 活跃/全部滴答数 */
    private fun readCpuStat(): Pair<Long, Long>? = runCatching {
        val line = File("/proc/stat").readText().lineSequence().first()
        val vals = line.split(Regex("\\s+")).drop(1).mapNotNull { it.toLongOrNull() }
        if (vals.size < 4) null else {
            val active = vals[0] + vals[1] + vals[2] // user + nice + system
            val total = vals.sum()
            active to total
        }
    }.getOrNull()

    /**
     * 采样系统整体 CPU 占用（0–100）。
     * 标准 Android 做法：比较两次 /proc/stat 的 active/total 差值。
     */
    suspend fun sampleSystemCpu(intervalMs: Long = 500): Float {
        val s1 = readCpuStat() ?: return 0f
        delay(intervalMs)
        val s2 = readCpuStat() ?: return 0f
        val activeDiff = s2.first - s1.first
        val totalDiff = s2.second - s1.second
        return if (totalDiff <= 0) 0f else (activeDiff * 100f / totalDiff).coerceIn(0f, 100f)
    }

    /**
     * 采样一次运行中进程。
     * [hasRoot] 由 ViewModel 一次性判定后传入，函数内不再重复起 su 验权。
     * 采用「单次快照 + 与上次快照的差值」计算 CPU，首帧 CPU=0 立即上屏。
     */
    suspend fun sample(hasRoot: Boolean): List<RawProcess> {
        return if (hasRoot) {
            sampleWithRoot().ifEmpty { sampleWithoutRoot() }
        } else {
            sampleWithoutRoot()
        }
    }

    /** 上一次 Root 快照（进程名可能含空格，用 pid+cmd 首段做 key） */
    private val prevRoot = mutableMapOf<Pair<Int, String>, ProcStat>()

    /** 上一次无 Root 快照 */
    private val prevNoRoot = mutableMapOf<Int, ProcStat>()

    /** 包名 → (名称, 图标) 的缓存，避免每个进程每帧都查 PackageManager */
    private val appMetaCache = mutableMapOf<String, Pair<String, Bitmap?>>()

    private fun appMeta(pkg: String): Pair<String, Bitmap?> {
        appMetaCache[pkg]?.let { return it }
        val info = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull()
        val pair = (info?.loadLabel(pm)?.toString() ?: pkg) to info?.loadIcon(pm)?.toBitmap()
        appMetaCache[pkg] = pair
        return pair
    }

    /** Root 路径：通过 su 读取 /proc，拿到所有用户/系统进程 */
    private suspend fun sampleWithRoot(): List<RawProcess> {
        val snap = snapshotRoot()
        if (snap.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val result = mutableListOf<RawProcess>()
        for ((key, cur) in snap) {
            val (pid, pkg) = key
            val prev = prevRoot[key]
            val cpu = if (prev != null && cur.ts > prev.ts) {
                val jiffies = (cur.utime + cur.stime) - (prev.utime + prev.stime)
                calcCpuPercent(jiffies, cur.ts - prev.ts)
            } else 0f

            val (label, icon) = appMeta(pkg)
            val memInfo = runCatching { am.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull() }.getOrNull()
            val memMb = (memInfo?.totalPss ?: 0) / 1024

            result.add(
                RawProcess(
                    pid = pid,
                    pkg = pkg,
                    label = label,
                    importance = 0,
                    icon = icon,
                    cpuPercent = cpu.coerceAtLeast(0f),
                    memoryMb = memMb
                )
            )
        }
        prevRoot.clear()
        prevRoot.putAll(snap)
        return result.sortedByDescending { it.cpuPercent }
    }

    /**
     * 一次性通过单条 su 命令把每个进程的原始 cmdline + stat 原样输出，
     * 回 Kotlin 统一解析（用 substringAfter(") ") 取 utime/stime，避免 shell 字段错位）。
     */
    private suspend fun snapshotRoot(): Map<Pair<Int, String>, ProcStat> {
        val cmd = buildString {
            append("for pid in /proc/[0-9]*; do ")
            append("p=\${pid#/proc/}; ")
            // 用 read -d '' 读取 cmdline 首段（遇到 NUL 即止），避免外部命令开销
            append("IFS= read -r -d '' c1 < \"\$pid/cmdline\" 2>/dev/null || true; ")
            append("[ -z \"\$c1\" ] && continue; ")
            // 读取 stat 首行
            append("read -r st < \"\$pid/stat\" 2>/dev/null || true; ")
            append("[ -z \"\$st\" ] && continue; ")
            append("printf '%s|%s|%s\\n' \"\$p\" \"\$c1\" \"\$st\"; ")
            append("done")
        }

        val out = RootShell.execAsync(cmd).out
        val now = System.currentTimeMillis()
        val map = mutableMapOf<Pair<Int, String>, ProcStat>()
        for (line in out.lineSequence()) {
            val parts = line.split("|", limit = 3)
            if (parts.size < 3) continue
            val pid = parts[0].toIntOrNull() ?: continue
            // cmdline 第一段即进程标识（去掉可能带上的 :service 后缀），保留原始点号
            val pkg = parts[1].trim().substringBefore(":")
            if (pkg.isBlank() || pkg.startsWith("/")) continue

            val stat = parts[2]
            val afterName = stat.substringAfter(") ")
            if (afterName == stat) continue // 没有 ")"，格式异常
            val f = afterName.split(" ")
            val utime = f.getOrNull(11)?.toLongOrNull() ?: continue
            val stime = f.getOrNull(12)?.toLongOrNull() ?: continue
            map[pid to pkg] = ProcStat(pid, utime, stime, now)
        }
        return map
    }

    /** 无 Root 路径：只能看到本应用（Android 10+ 限制） */
    private suspend fun sampleWithoutRoot(): List<RawProcess> {
        val running = am.runningAppProcesses ?: return emptyList()
        val now = System.currentTimeMillis()
        val result = mutableListOf<RawProcess>()
        for (rap in running) {
            val pid = rap.pid
            val pkg = rap.pkgList?.firstOrNull()
                ?: rap.processName?.split(":")?.firstOrNull()
                ?: continue
            if (pkg == context.packageName) continue

            val prev = prevNoRoot[pid]
            val cpu = if (prev != null && now > prev.ts) {
                val jiffies = readJiffies(pid)?.let { (it.first + it.second) - (prev.utime + prev.stime) } ?: 0L
                calcCpuPercent(jiffies, now - prev.ts)
            } else 0f

            val (label, icon) = appMeta(pkg)
            val memInfo = runCatching { am.getProcessMemoryInfo(intArrayOf(pid)).firstOrNull() }.getOrNull()
            val memMb = (memInfo?.totalPss ?: 0) / 1024

            result.add(
                RawProcess(
                    pid = pid,
                    pkg = pkg,
                    label = label,
                    importance = rap.importance,
                    icon = icon,
                    cpuPercent = cpu.coerceAtLeast(0f),
                    memoryMb = memMb
                )
            )
            readJiffies(pid)?.let { (u, s) -> prevNoRoot[pid] = ProcStat(pid, u, s, now) }
        }
        return result.sortedByDescending { it.cpuPercent }
    }

    /** 读取 /proc/<pid>/stat 的 utime+stime（jiffies），进程消失返回 null */
    private fun readJiffies(pid: Int): Pair<Long, Long>? = runCatching {
        val line = File("/proc/$pid/stat").readText()
        val afterName = line.substringAfter(") ")
        val parts = afterName.split(" ")
        (parts[11].toLongOrNull() ?: 0L) to (parts[12].toLongOrNull() ?: 0L)
    }.getOrNull()

    private data class ProcStat(val pid: Int, val utime: Long, val stime: Long, val ts: Long)

    /** Root 下获取目标包的 Activity/Service 快照 */
    suspend fun details(pkg: String): Pair<String, String> {
        val activities = RootShell.execAsync("dumpsys activity activities | grep -A 3 $pkg").out
        val services = RootShell.execAsync("dumpsys activity services $pkg").out
        return activities to services
    }

    /** 结束进程：优先 Root am force-stop，否则 killBackgroundProcesses */
    suspend fun kill(pkg: String): Boolean {
        return if (RootShell.isRootAvailable) {
            RootShell.execAsync("am force-stop $pkg").code == 0
        } else {
            am.killBackgroundProcesses(pkg)
            true
        }
    }
}
