package com.appslim.analyzer.root

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

/**
 * 非 Root 模式桥（报告 §4：Shizuku）
 *
 * 用于：dumpsys / package manager / activity manager，
 * 覆盖无 Root 设备的数据采集能力。
 */
object ShizukuBridge {

    val isAvailable: Boolean
        get() = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    val hasPermission: Boolean
        get() = runCatching {
            isAvailable && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)

    fun requestPermission(requestCode: Int = 1001) {
        if (isAvailable && !hasPermission) Shizuku.requestPermission(requestCode)
    }

    /**
     * 通过 Shizuku 执行 shell（如 dumpsys activity / dumpsys cpuinfo）。
     * 返回 null 表示 Shizuku 不可用或权限不足。
     */
    fun exec(cmd: String): String? {
        if (!hasPermission) return null
        return runCatching {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            )
            method.isAccessible = true
            val process = method.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
            process.inputStream.bufferedReader().readText().also { process.waitFor() }
        }.getOrNull()
    }

    /** 读取目标应用顶层 Activity（activity manager 通道） */
    fun topActivity(packageName: String): String? =
        exec("dumpsys activity activities | grep -E 'topResumedActivity|ResumedActivity'")
            ?.lineSequence()?.firstOrNull { it.contains(packageName) }
            ?.substringAfterLast('/')?.substringBefore(' ')?.trim()

    /** dumpsys meminfo：内存画像（性能对比数据源，报告 §12.4） */
    fun meminfo(packageName: String): String? = exec("dumpsys meminfo $packageName")
}
