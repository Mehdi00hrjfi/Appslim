package com.appslim.analyzer.lens.launcher

import android.app.ActivityManager
import com.appslim.analyzer.lens.PermissionLevel
import com.appslim.analyzer.lens.launcher.ActivityLauncher
import com.appslim.analyzer.lens.collector.CpuCollector
import com.appslim.analyzer.lens.collector.MemoryCollector
import com.appslim.analyzer.lens.collector.ProcessCollector
import com.appslim.analyzer.lens.model.ActivityResult

/**
 * 测试流程管理（v0.5.0 §6，v0.5.2 加固）
 * 单个 Activity：启动 → 等待进程 → 基线 3s → 稳定采样 10s（每 500ms）→ 输出指标。
 *
 * 加固点：
 * - 整体包 try/catch，任何一步异常都记为 FAILED 而非抛飞导致整页崩溃；
 * - 单 Activity 严格控制在 ~15s 预算内（含启动等待），绝不无限挂起；
 * - 优先单 Activity 测试，避免「一键全测」中一个卡死拖垮其余。
 */
class LaunchController(private val am: ActivityManager) {

    data class RawActivityMetrics(
        val status: ActivityResult.Status,
        val launchTimeMs: Long,
        val cpuBaseline: Float,
        val cpuAvg: Float,
        val cpuPeak: Float,
        val cpuP95: Float,
        val memBeforeMb: Int,
        val memAfterMb: Int,
        val errorMessage: String
    )

    /** 单个 Activity 测试总预算（毫秒） */
    private val TOTAL_BUDGET_MS = 15_000L

    private fun failed(launchTimeMs: Long, msg: String) = RawActivityMetrics(
        ActivityResult.Status.FAILED, launchTimeMs, 0f, 0f, 0f, 0f, 0, 0, msg
    )

    fun testActivity(
        packageName: String,
        activity: String,
        level: PermissionLevel
    ): RawActivityMetrics {
        val t0 = System.currentTimeMillis()
        return try {
            val (launchTimeMs, ok) = ActivityLauncher.launch(packageName, activity)
            if (!ok) return failed(launchTimeMs, "am start 被拒绝（可能未 exported 或需特定权限）")

            val waitBudget = (TOTAL_BUDGET_MS - (System.currentTimeMillis() - t0)).coerceIn(0L, 5000L)
            val waitedPid = if (waitBudget > 0) ProcessCollector.waitFor(packageName, waitBudget) else null
            val pid = waitedPid
                ?: return failed(launchTimeMs, "启动后未找到进程（可能崩溃 / 秒退 / 需登录）")

            // 基线 3s（受总预算约束）
            val baselineBudget = (TOTAL_BUDGET_MS - (System.currentTimeMillis() - t0)).coerceIn(0L, 3000L)
            if (baselineBudget <= 0) return failed(launchTimeMs, "测试超时")
            val cpuBaseline = sampleWindowPct(pid, level, baselineBudget)

            val memBefore = runCatching { MemoryCollector.sample(pid, packageName, level, am) }.getOrDefault(0)
            val samples = mutableListOf<Float>()
            var prev = CpuCollector.sample(pid, level)
            while (System.currentTimeMillis() - t0 < TOTAL_BUDGET_MS - 2000) {
                val left = TOTAL_BUDGET_MS - (System.currentTimeMillis() - t0)
                if (left <= 200L) break
                Thread.sleep(minOf(500L, left))
                val cur = CpuCollector.sample(pid, level)
                val pct = cur.directPct ?: run {
                    if (prev.jiffies > 0 && cur.jiffies > 0)
                        CpuCollector.deltaPct(prev.jiffies, cur.jiffies, 500) else 0f
                }
                samples.add(pct.coerceAtLeast(0f))
                prev = cur
            }
            val memAfter = runCatching { MemoryCollector.sample(pid, packageName, level, am) }.getOrDefault(0)

            val avg = if (samples.isEmpty()) 0f else samples.average().toFloat()
            val peak = samples.maxOrNull() ?: 0f
            val p95 = percentile(samples, 95)

            RawActivityMetrics(
                ActivityResult.Status.SUCCESS, launchTimeMs, cpuBaseline,
                avg, peak, p95, memBefore, memAfter, ""
            )
        } catch (e: Throwable) {
            // 任何异常都安全降级为 FAILED，绝不让协程抛飞导致整页崩溃
            failed(0, "测试异常：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** 在 durationMs 内采样两次，返回平均 CPU% */
    private fun sampleWindowPct(pid: Int, level: PermissionLevel, durationMs: Long): Float {
        val s1 = CpuCollector.sample(pid, level)
        Thread.sleep(durationMs)
        val s2 = CpuCollector.sample(pid, level)
        return s2.directPct ?: run {
            if (s1.jiffies > 0 && s2.jiffies > 0)
                CpuCollector.deltaPct(s1.jiffies, s2.jiffies, durationMs) else 0f
        }
    }

    private fun percentile(values: List<Float>, p: Int): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.sorted()
        val idx = ((sorted.size - 1) * p / 100f).toInt().coerceIn(0, sorted.size - 1)
        return sorted[idx]
    }
}
