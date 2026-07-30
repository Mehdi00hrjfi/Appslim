package com.appslim.analyzer.lens.model

/**
 * 检测结束状态（v0.5.4 新增，便于 UI 精准处理）
 */
enum class ProfileStatus {
    SUCCESS,        // 正常完成
    LAUNCH_FAILED,  // 无法启动目标应用
    PERMISSION_DENIED, // 当前权限不足以完成检测
    TIMEOUT,        // 超过总预算仍未完成
    ERROR           // 过程异常
}

/**
 * 应用启动行为检测结果（v0.5.3 重写「性能透镜」，v0.5.4 增加状态字段）
 * 记录检测期间出现的 Activity 及其资源占用、以及各进程的资源占用。
 */
data class ProfileResult(
    val packageName: String,
    val launchedActivity: String,
    val launchMs: Long,
    val launchedOk: Boolean,
    /** 检测期间出现过的 Activity（完整类名），按平均 CPU 降序 */
    val activities: List<ActivityUsage>,
    /** 检测期间采样到的进程资源占用，按平均 CPU 降序 */
    val processes: List<ProcessResource>,
    val errorMessage: String,
    val status: ProfileStatus = ProfileStatus.SUCCESS
) {
    data class ActivityUsage(
        val className: String,
        val avgCpu: Float,
        val peakCpu: Float
    )

    data class ProcessResource(
        val pid: Int,
        val processName: String,
        val avgCpu: Float,
        val peakCpu: Float,
        val memMb: Int
    )
}
