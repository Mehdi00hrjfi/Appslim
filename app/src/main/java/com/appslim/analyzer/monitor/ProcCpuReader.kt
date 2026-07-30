package com.appslim.analyzer.monitor

import android.os.SystemClock
import java.io.File

/**
 * CPU 调度分析（报告 §6）
 *
 * 通过 Linux /proc 接口读取线程级 CPU 数据：
 *   /proc/[pid]/task/             —— 线程列表
 *   /proc/[pid]/task/[tid]/stat   —— CPU 时间 / 调度状态 / 所在核心
 *
 * 对应报告 §13.2「top -H -p PID」的程序化实现。
 */
object ProcCpuReader {

    data class ThreadStat(
        val tid: Int,
        val name: String,      // comm：线程名（报告 §6：Thread Name）
        val state: Char,       // 调度状态 R/S/D/Z…
        val utime: Long,       // 用户态 jiffies
        val stime: Long,       // 内核态 jiffies
        val processor: Int     // CPU Core（报告 §6：CPU Core）
    ) {
        val totalJiffies: Long get() = utime + stime
    }

    private val CLK_TCK = 100L // Android 默认 USER_HZ = 100

    /** 读取进程全部线程的调度数据 */
    fun readThreads(pid: Int): List<ThreadStat> {
        val taskDir = File("/proc/$pid/task")
        val tids = taskDir.list()?.mapNotNull { it.toIntOrNull() } ?: return emptyList()
        return tids.mapNotNull { tid -> readStat(pid, tid) }
    }

    private fun readStat(pid: Int, tid: Int): ThreadStat? = runCatching {
        // stat 格式：pid (comm) state ppid … utime(14) stime(15) … processor(39)
        val raw = File("/proc/$pid/task/$tid/stat").readText()
        val l = raw.indexOf('(')
        val r = raw.lastIndexOf(')')
        val comm = raw.substring(l + 1, r)
        val f = raw.substring(r + 2).split(" ")
        ThreadStat(
            tid = tid,
            name = comm,
            state = f[0][0],
            utime = f[11].toLong(),   // 字段14 = 索引11
            stime = f[12].toLong(),   // 字段15 = 索引12
            processor = f[36].toInt() // 字段39 = 索引36
        )
    }.getOrNull()

    /** 采样计算线程 CPU 占用率（两次采样差值 / 间隔） */
    fun sampleCpuPercent(pid: Int, intervalMs: Long = 500): Map<Int, Float> {
        val t0 = readThreads(pid).associateBy { it.tid }
        val wall0 = SystemClock.elapsedRealtime()
        SystemClock.sleep(intervalMs)
        val t1 = readThreads(pid).associateBy { it.tid }
        val wallDt = (SystemClock.elapsedRealtime() - wall0) / 1000f
        if (wallDt <= 0f) return emptyMap()
        return t1.mapNotNull { (tid, cur) ->
            val prev = t0[tid] ?: return@mapNotNull null
            val dtSec = (cur.totalJiffies - prev.totalJiffies) / CLK_TCK.toFloat()
            tid to (dtSec / wallDt * 100f).coerceAtLeast(0f)
        }.toMap()
    }
}
