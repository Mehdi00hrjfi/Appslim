package com.appslim.analyzer.lens.model

import com.appslim.analyzer.lens.PermissionLevel

/** 单个应用的完整性能分析报告 */
data class PerformanceReport(
    val packageName: String,
    val label: String,
    val permissionLevel: PermissionLevel,
    val activities: List<ActivityResult>,
    val dexTags: List<String> = emptyList(),
    val scannedAt: Long = System.currentTimeMillis()
) {
    /** 按 CPU 峰值降序，方便定位高负载组件 */
    fun sortedByCpu(): List<ActivityResult> = activities.sortedByDescending { it.cpuPeak }
}
