package com.appslim.analyzer.lens.analyzer

import com.appslim.analyzer.lens.model.ResourceLevel

/**
 * 本地行为分析器（替代 AI，v0.5.0 §9）
 * 三层规则融合：① 名称关键词 ② API/Dex 标签 ③ 资源档位。
 */
object BehaviorAnalyzer {

    data class Analysis(
        val nameTags: List<String>,
        val apiTags: List<String>,
        val sdkTags: List<String>,
        val explanation: String,
        val resourceLevel: ResourceLevel
    )

    fun analyze(
        activityName: String,
        dexTags: List<String>,
        cpuAvg: Float,
        cpuPeak: Float,
        memAfterMb: Int
    ): Analysis {
        val simpleName = activityName.substringAfterLast('.')
        val nameTags = KeywordRule.match(simpleName)
        val level = ResourceLevel.fromCpu(cpuPeak)
        val heavy = ApiRule.heavyTags().intersect(dexTags.toSet())

        // 职能描述
        val funcText = when {
            nameTags.isNotEmpty() -> "可能在做「${nameTags.joinToString(" / ")}」相关职能"
            else -> "主职能无法从名称直接判断（建议结合 Dex 标签查看）"
        }

        // 能力佐证
        val apiText = if (dexTags.isNotEmpty()) {
            " Dex 检出：${ApiRule.describe(dexTags)}"
        } else ""

        // 资源评价
        val loadText = when (level) {
            ResourceLevel.HIGH -> "，CPU 处于高负载（峰值 ${"%.0f".format(cpuPeak)}%）"
            ResourceLevel.HEAVY -> "，CPU 偏重（峰值 ${"%.0f".format(cpuPeak)}%）"
            ResourceLevel.NORMAL -> "，CPU 属普通 UI 级别"
            ResourceLevel.LIGHT -> "，CPU 占用轻量"
        }
        val memText = if (memAfterMb >= 200) "；内存占用偏高（约 ${memAfterMb}MB）" else ""

        val explanation = "推测$funcText。$apiText$loadText$memText" +
                if (heavy.isNotEmpty()) "；涉及重计算能力（${heavy.joinToString("、")}）。" else "。"

        // SDK 归因（基于活动名所属包不至于，这里用 dex 线索）
        val sdkTags = SdkRule.fromDexTags(dexTags)

        return Analysis(nameTags, dexTags, sdkTags, explanation, level)
    }
}
