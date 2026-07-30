package com.appslim.analyzer.util

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃日志（v0.5.6 新增）
 *
 * 作用：
 *  - 安装全局 [Thread.setDefaultUncaughtExceptionHandler]，任何未捕获异常都会写入
 *    app 私有目录 files/crash/crash.log（无需存储权限），同时仍交给原 handler 让系统弹崩溃框；
 *  - 提供 [coroutineHandler] 给 ViewModel / Service 的协程作用域，捕获协程内未处理异常并落盘，
 *    避免「LaunchedEffect 里抛异常直接闪退」这类问题被吞掉又没法查；
 *  - [read] / [clear] 供 UI 展示与清空。
 *
 * 这样用户下次崩溃后，直接把应用内「崩溃日志」里的文本发回来即可定位，不必再靠猜。
 */
object CrashLogger {

    private const val TAG = "CrashLogger"
    private const val MAX_LINES = 500

    private lateinit var appCtx: Context

    fun install(context: Context) {
        appCtx = context.applicationContext
        runCatching { File(appCtx.filesDir, "crash").mkdirs() }
        val default = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, t ->
            logToFile("UNCAUGHT @ thread=${thread.name}", t)
            default?.uncaughtException(thread, t)
        }
        Log.i(TAG, "CrashLogger installed")
    }

    /** 给协程作用域用的异常处理器 */
    fun coroutineHandler() = CoroutineExceptionHandler { _, t ->
        logToFile("COROUTINE", t)
    }

    @Synchronized
    fun logToFile(tag: String, t: Throwable) {
        runCatching {
            val dir = File(appCtx.filesDir, "crash")
            dir.mkdirs()
            val file = File(dir, "crash.log")
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val sb = StringBuilder()
            sb.append("==== $ts  $tag ====\n")
            sb.append("SDK=${Build.VERSION.SDK_INT}  BRAND=${Build.BRAND}  MODEL=${Build.MODEL}  APP=${Build.VERSION.RELEASE}\n")
            sb.append(Log.getStackTraceString(t))
            sb.append("\n")
            val existing = if (file.exists()) file.readText() else ""
            val combined = existing + sb.toString()
            val lines = combined.lines()
            val trimmed = if (lines.size > MAX_LINES) lines.takeLast(MAX_LINES).joinToString("\n") else combined
            file.writeText(trimmed)
            Log.e(TAG, "logged crash: $tag", t)
        }
    }

    fun read(): String {
        val file = File(appCtx.filesDir, "crash/crash.log")
        return if (file.exists()) file.readText() else "（暂无崩溃日志，说明还没崩过 / 日志已清空）"
    }

    fun clear() {
        runCatching { File(appCtx.filesDir, "crash/crash.log").delete() }
    }
}
