package com.appslim.analyzer.lens.collector

import com.appslim.analyzer.lens.util.Shell

/** 启动耗时采集（v0.5.0 §6 Step 2 / §7） */
object LaunchTimeCollector {
    /** 执行 am start 并返回（耗时毫秒, 是否成功） */
    fun measure(packageName: String, activity: String): Pair<Long, Boolean> {
        val (out, ms) = Shell.execTimed("am start -n $packageName/$activity")
        val ok = !out.contains("Error", ignoreCase = true) &&
                !out.contains("Permission Denial", ignoreCase = true) &&
                !out.contains("Exception", ignoreCase = true)
        return ms to ok
    }
}
