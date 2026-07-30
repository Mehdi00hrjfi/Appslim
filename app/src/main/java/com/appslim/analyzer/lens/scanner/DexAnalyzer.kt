package com.appslim.analyzer.lens.scanner

import java.io.File
import java.util.zip.ZipFile
import java.util.zip.ZipEntry

/**
 * Dex 行为扫描（v0.5.0 §5 Dex 增强扫描）
 *
 * 不解压整个 APK，只读取其中的 classes*.dex 条目，
 * 以原始字节流检索 API 关键字符串，推断该应用用到的系统能力，
 * 用于提升行为分析器的解释准确率。
 */
class DexAnalyzer {

    /** 关键 API 字符串 → 行为标签 */
    private val keywords = mapOf(
        "android.hardware.camera" to "摄像头调用",
        "MediaCodec" to "音视频编解码",
        "MediaPlayer" to "媒体播放",
        "AudioRecord" to "音频采集",
        "LocationManager" to "定位服务",
        "LocationClient" to "定位服务",
        "WebView" to "网页渲染",
        "BluetoothAdapter" to "蓝牙通信",
        "SensorManager" to "传感器使用",
        "OpenGL" to "图形渲染",
        "RenderScript" to "并行计算",
        "Sqlite" to "本地数据库",
        "okhttp" to "网络请求",
        "retrofit" to "网络请求",
        "glide" to "图片加载",
        "fresco" to "图片加载",
        "exoplayer" to "视频播放",
        "zxing" to "二维码识别",
        "mlkit" to "机器学习推理"
    )

    /** 返回排序后的行为标签列表（去重） */
    fun analyze(apkPath: String): List<String> {
        val apk = File(apkPath)
        if (!apk.exists()) return emptyList()
        val found = LinkedHashSet<String>()
        runCatching {
            ZipFile(apk).use { zip ->
                val entries = zip.entries().toList().filterIsInstance<ZipEntry>()
                    .filter { it.name.matches(Regex("classes.*\\.dex$")) }
                for (entry in entries) {
                    zip.getInputStream(entry).use { stream ->
                        scanStream(stream, found)
                    }
                }
            }
        }
        return found.toList()
    }

    private fun scanStream(stream: java.io.InputStream, found: LinkedHashSet<String>) {
        val maxLen = keywords.keys.maxOf { it.length }
        val buf = ByteArray(1 shl 16) // 64KB
        var overlap = ""
        while (true) {
            val n = stream.read(buf)
            if (n <= 0) break
            // 用 ISO-8859-1 保留原始字节，便于子串检索
            var text = overlap + String(buf, 0, n, charset("ISO-8859-1"))
            for ((kw, tag) in keywords) {
                if (text.contains(kw)) found.add(tag)
            }
            // 保留尾部 overlap 避免关键字跨块被截断
            overlap = if (text.length > maxLen) text.takeLast(maxLen) else text
        }
    }
}
