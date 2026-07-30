package com.appslim.analyzer.analysis

/**
 * SDK 识别（报告 §5.3 / §3 分析引擎）
 *
 * 大量 SDK 利用 ContentProvider 的 attachInfo() 在应用启动时自动初始化，
 * 因此以 Provider 的包路径特征为主要识别线索，辅以 Service/Receiver 路径。
 */
object SdkIdentifier {

    data class SdkInfo(val name: String, val category: Category, val pattern: String)

    enum class Category(val label: String) { AD("广告"), STATS("统计"), PUSH("推送"), PAY("支付"), MAP("地图"), CONTENT("内容") }

    private val SDK_DB = listOf(
        SdkInfo("GDT 广点通广告", Category.AD, "com.qq.e.ads"),
        SdkInfo("穿山甲广告", Category.AD, "com.bytedance.sdk.openadsdk"),
        SdkInfo("友盟统计", Category.STATS, "com.umeng.analytics"),
        SdkInfo("神策数据", Category.STATS, "com.sensorsdata.analytics"),
        SdkInfo("个推推送", Category.PUSH, "com.igexin.push"),
        SdkInfo("极光推送", Category.PUSH, "cn.jpush.android"),
        SdkInfo("小米推送", Category.PUSH, "com.xiaomi.push"),
        SdkInfo("微信支付", Category.PAY, "com.tencent.mm.opensdk"),
        SdkInfo("高德地图", Category.MAP, "com.amap.api"),
        SdkInfo("短视频 SDK", Category.CONTENT, "com.ss.android.ugc.aweme")
    )

    fun identify(className: String): SdkInfo? =
        SDK_DB.firstOrNull { className.startsWith(it.pattern) }

    /** 识别一个应用内的全部第三方 SDK（输入：Manifest 扫描得到的组件类名列表） */
    fun identifyAll(componentClasses: Collection<String>): List<SdkInfo> =
        componentClasses.mapNotNull { identify(it) }.distinctBy { it.name }
}
