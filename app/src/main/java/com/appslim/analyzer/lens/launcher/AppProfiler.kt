package com.appslim.analyzer.lens.launcher

import android.app.ActivityManager
import com.appslim.analyzer.lens.PermissionLevel
import com.appslim.analyzer.lens.collector.CpuCollector
import com.appslim.analyzer.lens.collector.MemoryCollector
import com.appslim.analyzer.lens.model.ProfileResult
import com.appslim.analyzer.lens.model.ProfileState
import com.appslim.analyzer.lens.model.ProfileStatus
import com.appslim.analyzer.lens.util.Shell
import com.appslim.analyzer.root.RootShell
import kotlinx.coroutines.delay
import java.io.File
import kotlin.math.max

/**
 * 应用启动行为检测（v0.5.3 重写，v0.5.4 稳定性加固）
 *
 * 流程：启动目标 App → 前台运行 10s 持续采样 CPU/内存 + 记录 Activity 栈
 *       → 自动回到本应用 → 汇总「启动了哪些 Activity、哪些最吃资源」。
 *
 * v0.5.4 加固点：
 *  - targetPids 扫描结果缓存 2s，避免每 500ms 全扫 /proc；
 *  - 单个 PID 采样异常被隔离，不会再拖垮整轮检测；
 *  - dumpsys（Activity 栈）降频到每 2s 一次；
 *  - 通过 onState 回传阶段状态，UI 可显示「正在启动 / 采样 6/10s / 汇总」；
 *  - 全程 try/catch + 15s 预算兜底，异常降级为失败结果而非抛飞。
 */
class AppProfiler(private val am: ActivityManager) {

    private val OUR_PKG = "com.appslim.analyzer"
    private val PROFILE_MS = 10_000L
    private val BUDGET_MS = 15_000L
    private val TICK_MS = 500L
    private val PID_CACHE_MS = 2_000L
    private val ACTIVITY_CHECK_MS = 2_000L

    // /proc pid 扫描结果缓存（2s 内复用）
    private var lastPidScan = 0L
    private var cachedPids = emptyList<Int>()

    suspend fun profile(
        packageName: String,
        launcherActivity: String,
        level: PermissionLevel,
        onState: (ProfileState) -> Unit = {}
    ): ProfileResult {
        val t0 = System.currentTimeMillis()
        return try {
            // 1) 启动目标 App
            onState(ProfileState.Launching)
            val (launchedOk, launchMs) = launchTarget(packageName, launcherActivity)
            if (!launchedOk) {
                return ProfileResult(
                    packageName, launcherActivity, launchMs, false,
                    emptyList(), emptyList(),
                    "无法启动目标应用（am start 失败，可能未 exported 或需登录）",
                    ProfileStatus.LAUNCH_FAILED
                ).also { runCatching { Shell.exec("am start -n $OUR_PKG/$OUR_PKG.MainActivity") } }
            }

            // 2) 前台运行期间持续采样
            val pidSamples = mutableMapOf<Int, MutableList<Float>>()
            val pidPeak = mutableMapOf<Int, Float>()
            val pidName = mutableMapOf<Int, String>()
            val pidMem = mutableMapOf<Int, Int>()
            val activityCpu = mutableMapOf<String, MutableList<Float>>()
            val activityPeak = mutableMapOf<String, Float>()
            val activitiesSeen = LinkedHashSet<String>()
            var prev = mapOf<Int, CpuCollector.CpuSample>()
            var lastActivityCheck = 0L

            val deadline = System.currentTimeMillis() + PROFILE_MS
            while (System.currentTimeMillis() < deadline && System.currentTimeMillis() - t0 < BUDGET_MS) {
                val left = BUDGET_MS - (System.currentTimeMillis() - t0)
                if (left <= 200L) break

                onState(ProfileState.Sampling(((System.currentTimeMillis() - t0) / 1000).toInt().coerceIn(0, 10)))

                val pids = targetPids(packageName)
                // 单个 PID 异常隔离：任何进程读失败都跳过，不影响其余
                val curMap = pids.associateWithNotNull { pid ->
                    runCatching { CpuCollector.sample(pid, level) }.getOrNull()
                }
                val currentCpu = mutableMapOf<Int, Float>()
                for ((pid, cur) in curMap) {
                    val pct = prev[pid]?.let { p ->
                        if (p.jiffies > 0 && cur.jiffies > 0)
                            CpuCollector.deltaPct(p.jiffies, cur.jiffies, TICK_MS) else 0f
                    } ?: 0f
                    val safe = pct.coerceAtLeast(0f)
                    pidSamples.getOrPut(pid) { mutableListOf() }.add(safe)
                    pidPeak[pid] = max(pidPeak[pid] ?: 0f, safe)
                    pidName.getOrPut(pid) { processName(pid, packageName) }
                    pidMem[pid] = runCatching { MemoryCollector.sample(pid, packageName, level, am) }
                        .getOrDefault(pidMem[pid] ?: 0)
                    currentCpu[pid] = safe
                }
                prev = curMap

                // Activity 栈检测降频：每 2s 一次，避免频繁 dumpsys
                val now = System.currentTimeMillis()
                if (now - lastActivityCheck > ACTIVITY_CHECK_MS) {
                    lastActivityCheck = now
                    val top = topActivity(packageName, level)
                    if (top != null) {
                        activitiesSeen.add(top)
                        val c = currentCpu.values.maxOrNull() ?: 0f
                        activityCpu.getOrPut(top) { mutableListOf() }.add(c)
                        activityPeak[top] = max(activityPeak[top] ?: 0f, c)
                    }
                }
                delay(TICK_MS)
            }

            // 3) 回到本应用 + 汇总
            onState(ProfileState.Finishing)
            Shell.exec("am start -n $OUR_PKG/$OUR_PKG.MainActivity")

            val activities = activitiesSeen.map { a ->
                val s = activityCpu[a].orEmpty()
                ProfileResult.ActivityUsage(
                    className = a,
                    avgCpu = if (s.isEmpty()) 0f else s.average().toFloat(),
                    peakCpu = activityPeak[a] ?: 0f
                )
            }.sortedByDescending { it.avgCpu }

            val processes = pidSamples.map { (pid, s) ->
                ProfileResult.ProcessResource(
                    pid = pid,
                    processName = pidName[pid] ?: packageName,
                    avgCpu = if (s.isEmpty()) 0f else s.average().toFloat(),
                    peakCpu = pidPeak[pid] ?: 0f,
                    memMb = pidMem[pid] ?: 0
                )
            }.sortedByDescending { it.avgCpu }

            val timedOut = System.currentTimeMillis() - t0 >= BUDGET_MS
            ProfileResult(
                packageName, launcherActivity, launchMs, true, activities, processes, "",
                if (timedOut) ProfileStatus.TIMEOUT else ProfileStatus.SUCCESS
            )
        } catch (e: Throwable) {
            // 兜底：尽量回到本应用，返回失败结果而非抛飞
            runCatching { Shell.exec("am start -n $OUR_PKG/$OUR_PKG.MainActivity") }
            ProfileResult(
                packageName, launcherActivity, 0, false,
                emptyList(), emptyList(),
                "检测异常：" + (e.message ?: e.javaClass.simpleName),
                ProfileStatus.ERROR
            )
        }
    }

    /** 启动目标 App：优先 am start -n pkg/activity，否则退回 monkey 拉起 */
    private fun launchTarget(pkg: String, activity: String): Pair<Boolean, Long> {
        val cmd = if (activity.isNotBlank()) "am start -n $pkg/$activity"
        else "monkey -p $pkg -c android.intent.category.LAUNCHER 1"
        val (out, ms) = Shell.execTimed(cmd)
        val ok = out.contains("Starting", ignoreCase = true) ||
                out.contains("Events injected", ignoreCase = true)
        return ok to ms
    }

    /** 读取当前前台 Activity（Root/ADB 可读 dumpsys；普通模式返回 null 降级） */
    private suspend fun topActivity(pkg: String, level: PermissionLevel): String? = runCatching {
        val raw = when (level) {
            PermissionLevel.ROOT -> RootShell.execAsync("dumpsys activity activities").out
            PermissionLevel.ADB -> Shell.exec("dumpsys activity activities")
            PermissionLevel.NORMAL -> ""
        }
        if (raw.isBlank()) return null
        Regex("${Regex.escape(pkg)}/([\\w.$]+)").findAll(raw)
            .map { it.groupValues[1] }.firstOrNull()
    }.getOrNull()

    /** 扫描 /proc 找出目标包的全部进程 pid（含 :xxx 多进程）；结果缓存 2s 降低开销 */
    private fun targetPids(pkg: String): List<Int> {
        val now = System.currentTimeMillis()
        if (now - lastPidScan < PID_CACHE_MS && cachedPids.isNotEmpty()) return cachedPids
        lastPidScan = now
        cachedPids = runCatching {
            File("/proc").listFiles { f -> f.isDirectory }?.mapNotNull { dir ->
                val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
                val cmd = runCatching {
                    File(dir, "cmdline").readBytes().toString(Charsets.UTF_8).substringBefore('\u0000')
                }.getOrNull() ?: ""
                if (cmd == pkg || cmd.startsWith("$pkg:")) pid else null
            } ?: emptyList()
        }.getOrDefault(emptyList())
        return cachedPids
    }

    private fun processName(pid: Int, pkg: String): String = runCatching {
        val cmd = File("/proc/$pid/cmdline").readBytes().toString(Charsets.UTF_8).substringBefore('\u0000')
        if (cmd.isNotBlank()) cmd.substringBefore(':') else pkg
    }.getOrDefault(pkg)
}

/** List 扩展：逐元素计算，遇到 null 结果跳过（用于隔离单个 PID 采样异常） */
private inline fun <K, V> Iterable<K>.associateWithNotNull(block: (K) -> V?): Map<K, V> {
    val map = mutableMapOf<K, V>()
    for (item in this) {
        val value = block(item)
        if (value != null) map[item] = value
    }
    return map
}
