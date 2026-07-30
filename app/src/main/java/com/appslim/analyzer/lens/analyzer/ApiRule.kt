package com.appslim.analyzer.lens.analyzer

/**
 * 第二层：API 规则（v0.5.0 §9）
 * 基于 DexAnalyzer 产出的 API 行为标签，推断该应用用到的系统能力，
 * 用于佐证行为分析器的判断。
 */
object ApiRule {
    /** 将 Dex 行为标签转换为人类可读的能力描述 */
    fun describe(tags: List<String>): String {
        if (tags.isEmpty()) return "未从 Dex 中检出明显系统能力调用"
        return tags.joinToString("、")
    }

    /** 高负载嫌疑：这些能力通常伴随明显 CPU/内存开销 */
    fun heavyTags(): Set<String> = setOf(
        "音视频编解码", "摄像头调用", "图形渲染", "机器学习推理", "并行计算", "网页渲染"
    )
}
