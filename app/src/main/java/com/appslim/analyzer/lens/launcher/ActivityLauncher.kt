package com.appslim.analyzer.lens.launcher

import com.appslim.analyzer.lens.collector.LaunchTimeCollector

/**
 * Activity 启动控制（v0.5.0 §6）
 * 封装 am start -n package/activity，返回启动耗时与是否成功。
 */
object ActivityLauncher {
    fun launch(packageName: String, activity: String): Pair<Long, Boolean> =
        LaunchTimeCollector.measure(packageName, activity)
}
