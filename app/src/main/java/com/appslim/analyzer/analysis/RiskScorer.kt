package com.appslim.analyzer.analysis

import com.appslim.analyzer.data.model.ComponentMetric
import com.appslim.analyzer.data.model.RiskScore
import com.appslim.analyzer.data.model.RiskTier
import kotlin.math.min

/**
 * 垃圾模块识别算法（报告 §7）
 *
 * 风险 = CPU占用(≤20) + 启动频率(≤10) + 网络访问(≤20)
 *      + 后台驻留(≤20) + 用户无关程度(≤30)，满分 100。
 *
 * 判定：≥85 建议关闭；≥65 高风险；≥40 可优化；其余核心/安全。
 *
 * 注意（报告 §14.3）：CPU 高 ≠ 垃圾（如游戏渲染），
 * 因此引入 [ComponentMetric.coreAffinity] 结合用户行为加权。
 */
object RiskScorer {

    private const val CPU_FULL_MARK_AT = 35f   // CPU 峰值 35% 记满分 20
    private const val FREQ_FULL_MARK_AT = 20f  // 日均 20 次唤醒记满分 10
    private const val NET_FULL_MARK_AT = 60f   // 日耗 60MB 记满分 20
    private const val BG_FULL_MARK_AT = 180f   // 后台 3 小时记满分 20

    fun score(m: ComponentMetric): RiskScore = RiskScore(
        cpuPart = min(20f, m.cpuPeak / CPU_FULL_MARK_AT * 20f),
        freqPart = min(10f, m.launchesPerDay / FREQ_FULL_MARK_AT * 10f),
        netPart = min(20f, m.netMbPerDay / NET_FULL_MARK_AT * 20f),
        bgPart = min(20f, m.bgMinPerDay / BG_FULL_MARK_AT * 20f),
        nonCorePart = (1f - m.coreAffinity.coerceIn(0f, 1f)) * 30f
    )

    fun tier(score: Int): RiskTier = when {
        score >= 85 -> RiskTier.BLOCK
        score >= 65 -> RiskTier.HIGH
        score >= 40 -> RiskTier.MID
        else -> RiskTier.LOW
    }

    /**
     * 综合风险分 —— 运行时打分与静态行为分取高者。
     *
     * 这么设计是因为两者各有盲区：刚扫完还没跑过监控时，运行时数据全是 0，
     * 光看 score() 所有组件都是低风险，等于没分析；反过来，有些组件 Manifest
     * 上人畜无害，实际却在疯狂跑 CPU。取高者能同时兜住这两种情况。
     */
    fun combined(m: ComponentMetric): Int = maxOf(score(m).total, m.staticRisk)

    fun combinedTier(m: ComponentMetric): RiskTier = tier(combined(m))

    /** 一键优化：返回所有非核心（MID 及以上）组件 */
    fun pickBlockCandidates(metrics: List<ComponentMetric>): List<ComponentMetric> =
        metrics.filter { tier(score(it).total) != RiskTier.LOW }

    /**
     * 保守版一键优化：在综合风险达标的基础上，再要求 safeToBlock。
     *
     * 与 pickBlockCandidates 的区别在于会主动排掉桌面入口、前台服务、深链 ——
     * 这三类禁用后用户立刻就能察觉出问题，不该由「一键」来做决定。
     */
    fun pickSafeCandidates(metrics: List<ComponentMetric>): List<ComponentMetric> =
        metrics.filter { it.safeToBlock && combinedTier(it) != RiskTier.LOW }
            .sortedByDescending { combined(it) }
}
