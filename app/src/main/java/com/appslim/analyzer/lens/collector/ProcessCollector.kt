package com.appslim.analyzer.lens.collector

import com.appslim.analyzer.lens.util.Shell
import java.io.File

/**
 * 进程查询（v0.5.0 §4 / §6）
 * 优先使用 pidof；失败则扫描 /proc/[pid]/cmdline（无需 Root 即可读取）。
 */
object ProcessCollector {

    /** 返回目标包名对应的主 PID（多进程取第一个），无则 null */
    fun pidOf(packageName: String): Int? {
        val fromPidof = Shell.exec("pidof $packageName").trim()
            .split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
        if (fromPidof != null) return fromPidof

        return File("/proc").listFiles { f -> f.isDirectory }?.firstNotNullOfOrNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@firstNotNullOfOrNull null
            runCatching {
                val cmd = File(dir, "cmdline").readBytes()
                    .toString(Charsets.UTF_8).substringBefore('\u0000')
                if (cmd == packageName || cmd.startsWith("$packageName:")) pid else null
            }.getOrNull()
        }
    }

    /** 等待目标进程出现，最多等待 [timeoutMs] */
    fun waitFor(packageName: String, timeoutMs: Long = 4000): Int? {
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < timeoutMs) {
            pidOf(packageName)?.let { return it }
            Thread.sleep(200)
        }
        return null
    }
}
