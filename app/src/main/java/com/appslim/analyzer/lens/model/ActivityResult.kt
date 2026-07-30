package com.appslim.analyzer.lens.model

/** 资源等级（分析器输出，对应 v0.5.0 §9 资源规则） */
enum class ResourceLevel(val label: String) {
    LIGHT("轻量"),
    NORMAL("普通 UI"),
    HEAVY("重计算"),
    HIGH("高负载");

    companion object {
        fun fromCpu(cpuPct: Float): ResourceLevel = when {
            cpuPct < 5f -> LIGHT
            cpuPct < 25f -> NORMAL
            cpuPct < 60f -> HEAVY
            else -> HIGH
        }
    }
}

/** 单个 Activity 的测试结果与解释 */
data class ActivityResult(
    val className: String,
    val exported: Boolean,
    val launcher: Boolean,
    val status: Status,
    /** am start 耗时（毫秒） */
    val launchTimeMs: Long = 0,
    /** 基线阶段平均 CPU% */
    val cpuBaseline: Float = 0f,
    /** 稳定采样阶段统计 */
    val cpuAvg: Float = 0f,
    val cpuPeak: Float = 0f,
    val cpuP95: Float = 0f,
    /** 内存（MB） */
    val memBeforeMb: Int = 0,
    val memAfterMb: Int = 0,
    /** Dex 行为标签（来自 ApiRule） */
    val apiTags: List<String> = emptyList(),
    /** 名称关键词标签（来自 KeywordRule） */
    val nameTags: List<String> = emptyList(),
    /** 本地行为分析器给出的解释 */
    val explanation: String = "",
    val resourceLevel: ResourceLevel = ResourceLevel.LIGHT,
    val errorMessage: String = ""
) {
    enum class Status { SUCCESS, FAILED, SKIPPED }
}
