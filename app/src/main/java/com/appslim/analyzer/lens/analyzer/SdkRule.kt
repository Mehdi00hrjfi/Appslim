package com.appslim.analyzer.lens.analyzer

/**
 * 第三层辅助：SDK 识别（v0.5.0 §9 / v0.6 路线）
 * 根据包名与 Dex 标签识别常见厂商 SDK，辅助归因。
 */
object SdkRule {
    private val vendorMap = listOf(
        "com.tencent" to "腾讯系 SDK",
        "com.bytedance" to "字节系 SDK",
        "com.google.android.gms" to "Google Play 服务",
        "com.facebook" to "Meta SDK",
        "com.alipay" to "支付宝 SDK",
        "com.umeng" to "友盟 SDK",
        "com.baidu" to "百度 SDK"
    )

    private val dexSdk = listOf(
        "okhttp" to "Square OkHttp（网络）",
        "retrofit" to "Square Retrofit（网络）",
        "glide" to "Glide（图片）",
        "zxing" to "ZXing（二维码）",
        "exoplayer" to "ExoPlayer（视频）",
        "mlkit" to "ML Kit（机器学习）"
    )

    fun detect(packageName: String): List<String> {
        val out = vendorMap.mapNotNull { (pkg, label) ->
            if (packageName.contains(pkg)) label else null
        }.toMutableList()
        return out
    }

    fun fromDexTags(dexTags: List<String>): List<String> {
        return dexSdk.mapNotNull { (kw, label) ->
            if (dexTags.any { it.lowercase().contains(kw) }) label else null
        }
    }
}
