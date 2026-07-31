package com.appslim.analyzer.data.model

/** 组件类型（报告 §5 四大组件监控） */
enum class ComponentType { ACTIVITY, SERVICE, RECEIVER, PROVIDER }

/**
 * Activity 监控记录（报告 §5.1 数据结构）
 */
data class ActivityRecord(
    val packageName: String,
    val className: String,
    val startTime: Long,
    val cpuUsage: Float,
    val risk: Int
)

/**
 * 统一的组件运行时画像 —— 数据采集层的输出（报告 §5）
 *
 * @param cpuPeak      CPU 峰值占用（%）
 * @param launchesPerDay 日均启动/唤醒次数
 * @param netMbPerDay  日均网络流量（MB）
 * @param bgMinPerDay  日均后台驻留（分钟）
 * @param coreAffinity 与核心功能的相关度 0~1（1 = 核心功能，报告 §14.3：需结合用户行为）
 * @param behaviorTags 行为特征标签（由 BehaviorClassifier 产出，不依赖 SDK 名单）
 * @param staticRisk   仅凭 Manifest 得出的静态风险分，没有运行时数据时用它排序
 * @param safeToBlock  是否可以放心禁用；false 表示会造成用户可感知的功能损失
 * @param sideEffect   禁用后的具体后果说明，展示给用户看
 */
data class ComponentMetric(
    val packageName: String,
    val className: String,
    val type: ComponentType,
    val description: String = "",
    val cpuPeak: Float = 0f,
    val launchesPerDay: Float = 0f,
    val netMbPerDay: Float = 0f,
    val bgMinPerDay: Float = 0f,
    val coreAffinity: Float = 0.5f,
    val sdkName: String? = null,
    val behaviorTags: List<String> = emptyList(),
    val staticRisk: Int = 0,
    val safeToBlock: Boolean = false,
    val sideEffect: String = ""
)

/** 风险评分结果（报告 §7 评分模型） */
data class RiskScore(
    val cpuPart: Float,
    val freqPart: Float,
    val netPart: Float,
    val bgPart: Float,
    val nonCorePart: Float
) {
    val total: Int get() = (cpuPart + freqPart + netPart + bgPart + nonCorePart).toInt()
}

/** 风险等级与处置建议 */
enum class RiskTier(val label: String) {
    BLOCK("建议关闭"), HIGH("高风险"), MID("可优化"), LOW("核心/安全")
}

/** 依赖图节点（报告 §8 智能依赖分析） */
data class DependencyNode(
    val className: String,
    val type: ComponentType,
    val edges: MutableList<String> = mutableListOf() // 被本组件拉起的下游组件
)

/** 沙盒测试结论（报告 §12.2） */
data class SandboxResult(
    val className: String,
    val crashed: Boolean,
    val anr: Boolean,
    val coreFunctionBroken: Boolean
) {
    /** 绿色 = 可以禁用；红色 = 不建议禁用 */
    val green: Boolean get() = !crashed && !anr && !coreFunctionBroken
}

/** 阻断规则（报告 §10 自动生成配置） */
data class BlockRules(
    val targetPackage: String,
    val blockActivity: List<String> = emptyList(),
    val blockService: List<String> = emptyList(),
    val blockProvider: List<String> = emptyList(),
    val blockReceiver: List<String> = emptyList()
)
