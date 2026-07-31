package com.appslim.analyzer.ui

import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.collection.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 图标内存缓存（MT Manager 式异步加载的核心）：
 *
 * - 图标 **不进** 应用列表数据流（StateFlow），否则每次刷新列表都要重解上百个 Bitmap；
 * - 列表卡片在自身进入组合（可见）时才调 [load]，只解「看得见」的那十几个图标；
 * - 解码结果按包名留在 LRU 里，滚回来、二次进页都直接命中，不再碰 APK。
 *
 * 不落磁盘：图标随 APK 版本变化，且内存 LRU 已足够覆盖一屏 + 滚动回看，磁盘缓存收益低、
 * 还要处理版本失效，反而拖累。这里把钱花在「只解可见项」上。
 */
object IconCache {

    private const val MAX_ENTRIES = 400

    private val mem = LruCache<String, Bitmap>(MAX_ENTRIES)

    /** 同步读内存命中（UI 首帧用，避免闪烁占位） */
    fun get(pkg: String): Bitmap? = synchronized(mem) { mem.get(pkg) }

    private fun put(pkg: String, bmp: Bitmap) = synchronized(mem) { mem.put(pkg, bmp) }

    /**
     * 异步解码图标：内存命中直接返回；否则切 IO 线程用 ApplicationInfo.loadIcon 解码，
     * 失败（如停用/系统壳包）返回 null，UI 用占位图兜底。解码成功写入 LRU。
     */
    suspend fun load(pm: PackageManager, pkg: String, apkPath: String): Bitmap? {
        get(pkg)?.let { return it }
        return withContext(Dispatchers.IO) {
            val bmp = runCatching {
                val ai = pm.getApplicationInfo(pkg, 0)
                ai.loadIcon(pm).toBitmap()
            }.getOrNull()
            bmp?.let { put(pkg, it) }
            bmp
        }
    }
}
