package com.appslim.analyzer.analysis

import com.appslim.analyzer.data.model.ComponentType
import com.appslim.analyzer.data.model.DependencyNode
import com.appslim.analyzer.data.model.SandboxResult

/**
 * 智能依赖分析（报告 §8）
 *
 * 不能简单删除组件 —— 先基于运行时调用记录构建组件关系图，
 * 再通过「阻断演练」（沙盒测试）观察崩溃 / ANR / 功能异常，
 * 最终输出安全等级（绿 = 可以禁用，红 = 不建议禁用）。
 */
class DependencyAnalyzer {

    private val nodes = mutableMapOf<String, DependencyNode>()

    /** 记录一次组件拉起关系，如 MainActivity -> startService(VideoService) */
    fun recordEdge(fromClass: String, fromType: ComponentType, toClass: String, toType: ComponentType) {
        val from = nodes.getOrPut(fromClass) { DependencyNode(fromClass, fromType) }
        nodes.getOrPut(toClass) { DependencyNode(toClass, toType) }
        if (toClass !in from.edges) from.edges.add(toClass)
    }

    fun graph(): Map<String, DependencyNode> = nodes.toMap()

    /** 强依赖计数：有多少上游组件（尤其核心组件）依赖目标组件 */
    fun inboundCount(className: String): Int =
        nodes.values.count { className in it.edges }

    /**
     * 阻断演练评估（报告 §8「关闭 VideoService 观察是否崩溃/ANR/功能异常」）
     * @param coreClasses 核心功能组件集合（白名单，报告 §14.2）
     */
    fun evaluate(target: String, coreClasses: Set<String>, result: SandboxResult): Verdict {
        if (target in coreClasses) return Verdict(target, green = false, reason = "核心功能组件，白名单保护")
        if (result.crashed) return Verdict(target, green = false, reason = "阻断后发生崩溃")
        if (result.anr) return Verdict(target, green = false, reason = "阻断后主线程 ANR")
        if (result.coreFunctionBroken) return Verdict(target, green = false, reason = "核心功能遍历未通过")
        return Verdict(target, green = true, reason = "回归全部通过，可以禁用")
    }

    data class Verdict(val className: String, val green: Boolean, val reason: String)
}
