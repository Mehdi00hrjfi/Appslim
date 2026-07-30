package com.appslim.analyzer.lens.analyzer

/**
 * 第一层：名称关键词规则（v0.5.0 §9）
 * 根据 Activity 简单类名推测其职能。
 */
object KeywordRule {
    private val map = listOf(
        listOf("camera", "scan", "qr", "capture", "photo") to "摄像头/扫码",
        listOf("video", "player", "media", "live") to "视频播放",
        listOf("map", "location", "gps", "navi", "route") to "地图定位",
        listOf("web", "webview", "browser", "h5") to "网页渲染",
        listOf("login", "auth", "account", "sign", "passport") to "登录账号",
        listOf("ad", "splash", "ads", "reward") to "广告展示",
        listOf("pay", "wallet", "cashier", "order") to "支付交易",
        listOf("chat", "message", "conversation", "im") to "聊天消息",
        listOf("push", "notify", "notification") to "推送通知",
        listOf("share", "forward") to "分享",
        listOf("setting", "setup", "config", "pref") to "设置",
        listOf("main", "launch", "home", "splash", "welcome") to "主界面"
    )

    /** 返回匹配到的职能标签（可多个） */
    fun match(activitySimpleName: String): List<String> {
        val lower = activitySimpleName.lowercase()
        return map.mapNotNull { (keys, label) ->
            if (keys.any { lower.contains(it) }) label else null
        }
    }
}
