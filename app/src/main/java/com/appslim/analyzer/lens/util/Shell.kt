package com.appslim.analyzer.lens.util

/** 非 Root 普通 shell 执行（am start / pidof / dumpsys 等） */
object Shell {
    fun exec(cmd: String): String = runCatching {
        val p = Runtime.getRuntime().exec(cmd)
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        p.waitFor()
        if (out.isBlank()) err else out
    }.getOrDefault("")

    /** 执行并返回耗时（毫秒），用于启动耗时统计 */
    fun execTimed(cmd: String): Pair<String, Long> {
        val t0 = System.currentTimeMillis()
        val out = exec(cmd)
        return out to (System.currentTimeMillis() - t0)
    }
}
