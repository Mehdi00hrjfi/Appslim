package com.appslim.analyzer.lens.launcher

import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.delay

/**
 * Activity 采样器 —— 原样移植自 APPLENS（Horizen5/APPLENS: utils/ActivitySampler.kt）。
 *
 * 采样策略（与 APPLENS 完全一致）：
 *  1. 优先用 UsageStatsManager.queryEvents 取最近前台 Activity（普通模式即可，只需「使用情况访问」权限）；
 *  2. 取不到再用 dumpsys activity activities 兜底；
 *  3. 每 500ms 采样一次、持续 10s（默认 20 个采样点）；
 *  4. CPU / 内存用 top / dumpsys meminfo（有 root 则走 su）。
 *
 * 不做任何 /proc 扫描，避免跨 UID 读取在 HyperOS 上引发异常。
 */
class ApplensSampler(private val context: Context) {

    data class Config(
        val durationMs: Long = 10_000L,
        val intervalMs: Long = 500L
    )

    /** 采样一次得到的单条数据 */
    data class ActivitySample(
        val timestamp: Long,
        val packageName: String,
        val activityName: String,
        val activityFullName: String,
        val cpuUsage: Float,
        val memUsageKb: Long
    )

    /** 聚合后的单 Activity 统计 */
    data class ActivityStat(
        val activityFullName: String,
        val packageName: String,
        val activityName: String,
        val count: Int,
        val totalDurationMs: Long,
        val avgCpu: Float,
        val maxCpu: Float,
        val avgMemKb: Long,
        val firstSeen: Long,
        val lastSeen: Long
    )

    data class SamplingReport(
        val packageName: String,
        val appLabel: String,
        val startTime: Long,
        val endTime: Long,
        val durationMs: Long,
        val samples: List<ActivitySample>,
        val stats: List<ActivityStat>
    )

    /** 是否拥有「使用情况访问」权限 */
    fun hasUsageStatsPermission(): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(),
            context.packageName
        )
        return mode == android.app.AppOpsManager.MODE_ALLOWED
    }

    /** 跳转到「使用情况访问」权限页 */
    fun requestUsageStatsPermission() {
        val intent = android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    /**
     * 执行一次完整采样
     * @param onTick 每次采样回调（用于 UI 进度更新）
     */
    suspend fun sample(
        packageName: String,
        appLabel: String,
        config: Config = Config(),
        onTick: (Int, Int, ActivitySample?) -> Unit = { _, _, _ -> }
    ): SamplingReport {
        val startTime = System.currentTimeMillis()
        val samples = mutableListOf<ActivitySample>()
        val totalTicks = (config.durationMs / config.intervalMs).toInt().coerceAtLeast(1)

        for (i in 0 until totalTicks) {
            val sample = takeOneSample(packageName, startTime + i * config.intervalMs)
            if (sample != null) samples.add(sample)
            onTick(i + 1, totalTicks, sample)
            if (i < totalTicks - 1) {
                delay(config.intervalMs)
            }
        }
        val endTime = System.currentTimeMillis()
        val stats = aggregate(samples)
        return SamplingReport(
            packageName = packageName,
            appLabel = appLabel,
            startTime = startTime,
            endTime = endTime,
            durationMs = endTime - startTime,
            samples = samples,
            stats = stats
        )
    }

    /** 采样一次 */
    private fun takeOneSample(targetPackage: String, ts: Long): ActivitySample? {
        // 1. 通过 UsageStatsManager 获取最新前台 Activity
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(now - 2000, now)
        val event = android.app.usage.UsageEvents.Event()
        var pkg: String? = null
        var cls: String? = null
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            if (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND &&
                event.packageName == targetPackage
            ) {
                pkg = event.packageName
                cls = event.className
            }
        }

        // 2. 如果 UsageStats 没拿到，用 dumpsys 兜底
        if (pkg == null || cls == null) {
            val r = ApplensShell.exec(
                "dumpsys activity activities 2>/dev/null | grep -E 'ResumedActivity|mResumedActivity' | head -1",
                useRoot = ApplensShell.isRootAvailable
            )
            if (r.success) {
                val line = r.output.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
                val m = Regex("([a-zA-Z0-9_.]+)/([a-zA-Z0-9_.$]+)").find(line)
                if (m != null) {
                    pkg = m.groupValues[1]
                    cls = if (m.groupValues[2].startsWith(".")) {
                        pkg + m.groupValues[2]
                    } else m.groupValues[2]
                    if (pkg != targetPackage) {
                        pkg = null
                    }
                }
            }
        }

        if (pkg == null || cls == null) return null

        val full = if (cls.startsWith(".")) pkg + cls else cls
        val short = full.removePrefix("$pkg.")

        // 3. 获取 CPU / 内存（top / dumpsys meminfo，有 root 走 su）
        var cpu = 0f
        var mem = 0L
        val topRes = ApplensShell.exec(
            "top -b -n 1 -o PID,%,%MEM,CMDLINE 2>/dev/null | grep -E '${Regex.escape(pkg)}' | head -1",
            useRoot = ApplensShell.isRootAvailable
        )
        if (topRes.success) {
            val line = topRes.output.lineSequence().firstOrNull { it.isNotBlank() }
            if (line != null) {
                val cols = line.trim().split(Regex("\\s+"))
                for (c in cols) {
                    val v = c.removeSuffix("%").toFloatOrNull()
                    if (v != null && c.contains("%") && cpu == 0f) cpu = v
                }
                for (c in cols) {
                    if (c.endsWith("M") || c.endsWith("K")) {
                        val v = c.dropLast(1).toFloatOrNull()
                        if (v != null) {
                            mem = if (c.endsWith("M")) (v * 1024).toLong() else v.toLong()
                            break
                        }
                    }
                }
            }
        }
        if (mem == 0L) {
            val memRes = ApplensShell.exec(
                "dumpsys meminfo $pkg 2>/dev/null | grep -E 'TOTAL PSS|TOTAL' | head -1",
                useRoot = ApplensShell.isRootAvailable
            )
            if (memRes.success) {
                val m = Regex("(\\d+)").find(memRes.output)
                if (m != null) mem = m.groupValues[1].toLong()
            }
        }

        return ActivitySample(
            timestamp = ts,
            packageName = pkg,
            activityName = short,
            activityFullName = full,
            cpuUsage = cpu,
            memUsageKb = mem
        )
    }

    /** 聚合统计 */
    private fun aggregate(samples: List<ActivitySample>): List<ActivityStat> {
        if (samples.isEmpty()) return emptyList()
        val byAct = samples.groupBy { it.activityFullName }
        return byAct.map { (full, list) ->
            val p = list.first()
            ActivityStat(
                activityFullName = full,
                packageName = p.packageName,
                activityName = p.activityName,
                count = list.size,
                totalDurationMs = list.size.toLong() * 500L,
                avgCpu = list.map { it.cpuUsage }.average().toFloat(),
                maxCpu = list.maxOf { it.cpuUsage },
                avgMemKb = list.map { it.memUsageKb }.average().toLong(),
                firstSeen = list.first().timestamp,
                lastSeen = list.last().timestamp
            )
        }.sortedByDescending { it.count }
    }
}
