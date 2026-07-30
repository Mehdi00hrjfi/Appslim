package com.appslim.analyzer.lens.launcher

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Shell 执行工具 —— 原样移植自 APPLENS（Horizen5/APPLENS: utils/ShellUtils.kt）。
 * 优先尝试 root（su），失败则降级普通 shell（sh）。
 */
object ApplensShell {

    private const val TAG = "ApplensShell"

    data class Result(
        val success: Boolean,
        val output: String,
        val error: String,
        val code: Int
    )

    /** 是否拥有 root 权限（懒加载，首次访问时探测一次） */
    val isRootAvailable: Boolean by lazy {
        try {
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "id"))
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            val code = p.waitFor()
            code == 0 && out.contains("uid=0")
        } catch (e: Exception) {
            Log.w(TAG, "root 不可用: ${e.message}")
            false
        }
    }

    fun exec(cmd: String, useRoot: Boolean = true): Result {
        val parts = if (useRoot && isRootAvailable) {
            arrayOf("su", "-c", cmd)
        } else {
            arrayOf("sh", "-c", cmd)
        }
        return try {
            val p = Runtime.getRuntime().exec(parts)
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            val err = BufferedReader(InputStreamReader(p.errorStream)).readText()
            val code = p.waitFor()
            Result(code == 0, out, err, code)
        } catch (e: Exception) {
            Log.e(TAG, "exec 失败: ${e.message}")
            Result(false, "", e.message ?: "unknown", -1)
        }
    }

    fun execBatch(cmds: List<String>, useRoot: Boolean = true): Result {
        val script = cmds.joinToString("\n")
        val parts = if (useRoot && isRootAvailable) {
            arrayOf("su", "-c", script)
        } else {
            arrayOf("sh", "-c", script)
        }
        return try {
            val p = Runtime.getRuntime().exec(parts)
            val out = BufferedReader(InputStreamReader(p.inputStream)).readText()
            val err = BufferedReader(InputStreamReader(p.errorStream)).readText()
            val code = p.waitFor()
            Result(code == 0, out, err, code)
        } catch (e: Exception) {
            Result(false, "", e.message ?: "unknown", -1)
        }
    }
}
