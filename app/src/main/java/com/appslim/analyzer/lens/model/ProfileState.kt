package com.appslim.analyzer.lens.model

/**
 * 检测阶段状态（v0.5.4 新增），用于给 UI 反馈「正在做什么」
 */
sealed class ProfileState {
    /** 空闲 / 未开始 */
    object Idle : ProfileState()

    /** 正在启动目标应用 */
    object Launching : ProfileState()

    /** 正在采样，elapsedSec 为已采样秒数（0–10） */
    data class Sampling(val elapsedSec: Int) : ProfileState()

    /** 采样完成，正在汇总 */
    object Finishing : ProfileState()

    /** 全部完成（结果已就绪） */
    object Done : ProfileState()
}
