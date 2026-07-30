package com.appslim.analyzer.root

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.DataOutputStream
import java.io.InputStreamReader

/**
 * Root 模式执行器（报告 §4：Magisk / KernelSU）
 *
 * 用于：读取任意进程信息、写入 /data/misc/appslim 规则、执行 dumpsys。
 */
object RootShell {

    data class Result(val code: Int, val out: String, val err: String)

    val isRootAvailable: Boolean
        get() = runCatching { exec("id").code == 0 }.getOrDefault(false)

    /** 以 root 执行 shell 命令 */
    suspend fun execAsync(cmd: String): Result = withContext(Dispatchers.IO) { exec(cmd) }

    fun exec(cmd: String): Result = runCatching {
        val p = Runtime.getRuntime().exec("su")
        DataOutputStream(p.outputStream).use {
            it.writeBytes("$cmd\n")
            it.writeBytes("exit\n")
            it.flush()
        }
        val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
        val err = BufferedReader(InputStreamReader(p.errorStream)).readText()
        Result(p.waitFor(), out.trim(), err.trim())
    }.getOrElse { Result(-1, "", it.message ?: "exec failed") }

    /** 下发规则到 Hook 模块读取目录 */
    suspend fun installRules(packageName: String, rulesJson: String): Result {
        val path = "/data/misc/appslim"
        execAsync("mkdir -p $path && chmod 755 $path")
        val tmp = "/data/local/tmp/$packageName.json"
        return execAsync("echo '$rulesJson' > $tmp && mv $tmp $path/$packageName.json && chmod 644 $path/$packageName.json")
    }

    /** dumpsys（Root 通道，报告 §4） */
    suspend fun dumpsys(service: String, args: String = ""): Result =
        execAsync("dumpsys $service $args")
}
