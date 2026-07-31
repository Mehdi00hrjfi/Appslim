package com.appslim.analyzer.analysis

import android.content.Intent
import com.appslim.analyzer.data.model.ComponentType
import com.appslim.analyzer.monitor.ManifestScanner

/**
 * 行为特征分类器 —— 不看名字，只看它能干什么。
 *
 * 【为什么不能只靠 SDK 名单】
 * SdkIdentifier 里现在有 10 条规则，靠包名前缀匹配。这套东西有个天生的问题：
 *   - 新 SDK 层出不穷，名单永远滞后；
 *   - 加固/混淆之后包名变成 a.b.c，前缀匹配直接失效；
 *   - 厂商自研的保活组件根本不在任何公开名单里。
 *
 * Blocker（lihenggui/blocker）的做法值得抄：不管组件叫什么名字，
 * 只看它在 Manifest 里声明了哪些能力。声明了 BOOT_COMPLETED 就是自启，
 * 声明了 foregroundServiceType 就是前台服务 —— 这些特征混淆不掉，
 * 因为它们是系统识别组件的依据，改了组件就不工作了。
 *
 * 两套机制是互补的：名单命中时给出具体的 SDK 名（用户看得懂），
 * 名单没命中时行为分类兜底（保证不漏）。
 */
object BehaviorClassifier {

    /**
     * 组件行为标签。
     *
     * risk 是这个标签本身的耗电/隐私权重（0~100），用于在没有运行时数据
     * 的情况下先给个静态排序。有运行时数据后由 RiskScorer 接管。
     */
    enum class Behavior(
        val label: String,
        val risk: Int,
        /** 这个标签意味着什么，直接展示给用户 */
        val explain: String,
    ) {
        LAUNCHER(
            "桌面入口", 0,
            "桌面图标就是它。禁用后应用图标会从桌面消失，只能从设置里打开。"
        ),
        FOREGROUND_SERVICE(
            "前台服务", 20,
            "会显示常驻通知的服务，用于播放、下载、定位这类持续任务。" +
                "禁用可能导致任务中断或应用崩溃，谨慎处理。"
        ),
        AUTO_START(
            "开机自启", 85,
            "监听开机完成、解锁屏幕等事件，用户没打开应用它也会被拉起来。" +
                "这是后台耗电的主要来源，绝大多数情况可以安全禁用。"
        ),
        WAKEUP_LISTENER(
            "频繁唤醒", 70,
            "监听网络切换、充电、电量变化等高频系统广播，每次都会唤醒应用进程。" +
                "禁用后应用仍能正常使用，只是不再实时响应这些变化。"
        ),
        PUSH_SERVICE(
            "推送服务", 60,
            "接收厂商或第三方推送通道的消息。禁用后收不到推送通知，" +
                "但应用打开时的消息不受影响。"
        ),
        WAKELOCK(
            "持有唤醒锁", 75,
            "应用申请了 WAKE_LOCK 或精确闹钟权限，这类组件能阻止设备休眠。"
        ),
        DANGEROUS_PROVIDER(
            "开放数据接口", 65,
            "对外暴露的 ContentProvider 且授予临时 URI 权限，" +
                "其他应用可能借此读取数据。"
        ),
        EXPORTED_NO_PERM(
            "无保护对外暴露", 55,
            "exported=true 且没有声明权限，任何第三方应用都能直接调起它。" +
                "常被用作应用互相拉活的入口。"
        ),
        DEEPLINK(
            "深链入口", 15,
            "能被浏览器或其他应用通过链接唤起。禁用后相关链接跳转会失效。"
        ),
        ISOLATED_PROCESS(
            "独立进程", 40,
            "运行在单独进程里。这类组件禁用后释放的是整个进程的内存，收益更明显。"
        ),
        DIRECT_BOOT(
            "解锁前运行", 50,
            "声明了直接启动感知，设备开机后不解锁就能运行。"
        ),
        ;
    }

    /** 单个组件的分类结果 */
    data class Result(
        val className: String,
        val behaviors: List<Behavior>,
        /** 名单命中的 SDK，可能为空 */
        val sdk: SdkIdentifier.SdkInfo?,
    ) {
        /** 取权重最高的标签作为主标签 */
        val primary: Behavior? get() = behaviors.maxByOrNull { it.risk }

        /** 静态风险分：主标签权重打底，其余标签每个加 5，上限 100 */
        val staticRisk: Int
            get() = if (behaviors.isEmpty()) 0
            else minOf(100, (primary?.risk ?: 0) + (behaviors.size - 1) * 5)

        /**
         * 是否可以放心禁用。
         *
         * 三个否决项：桌面入口（图标会没）、前台服务（可能崩）、深链（跳转失效）。
         * 这三个都是用户能立刻感知到的功能损失，不该出现在「一键优化」里。
         */
        val safeToBlock: Boolean
            get() = behaviors.isNotEmpty() &&
                Behavior.LAUNCHER !in behaviors &&
                Behavior.FOREGROUND_SERVICE !in behaviors &&
                Behavior.DEEPLINK !in behaviors

        /** 禁用后的副作用说明，拼接所有标签的解释 */
        val sideEffect: String
            get() = when {
                behaviors.isEmpty() -> "未识别到明确行为特征，禁用影响未知，建议先观察。"
                else -> behaviors.sortedByDescending { it.risk }
                    .joinToString("\n") { "· ${it.label}：${it.explain}" }
            }
    }

    // ---- 判定用的常量集合 ----

    /** 命中即视为自启入口 */
    private val AUTO_START_ACTIONS = setOf(
        Intent.ACTION_BOOT_COMPLETED,
        "android.intent.action.QUICKBOOT_POWERON",
        Intent.ACTION_MY_PACKAGE_REPLACED,
        Intent.ACTION_USER_PRESENT,
        Intent.ACTION_PACKAGE_ADDED,
        Intent.ACTION_PACKAGE_REPLACED,
    )

    /** 命中即视为高频唤醒源 */
    private val WAKEUP_ACTIONS = setOf(
        "android.net.conn.CONNECTIVITY_CHANGE",
        "android.net.wifi.STATE_CHANGE",
        Intent.ACTION_SCREEN_ON,
        Intent.ACTION_POWER_CONNECTED,
        Intent.ACTION_BATTERY_CHANGED,
        Intent.ACTION_TIME_TICK,
        Intent.ACTION_MEDIA_MOUNTED,
        Intent.ACTION_LOCALE_CHANGED,
    )

    /** 推送通道 action，探针查出来的 */
    private val PUSH_ACTIONS = ManifestScanner.SERVICE_PROBES.toSet()

    /**
     * 推送服务的类名兜底特征。
     *
     * 这里破例用了名字匹配 —— 因为 FirebaseMessagingService 这类基类
     * 是必须被继承且不能混淆的（系统按类名反射查找），子类名虽然可以混淆，
     * 但实践中厂商 SDK 基本都保留了这些后缀。属于低成本高召回的补充。
     */
    private val PUSH_NAME_HINTS = listOf(
        "MessagingService", "PushService", "PushReceiver",
        "MiPushMessageReceiver", "HmsMessageService",
    )

    /** 阻止休眠相关的应用级权限 */
    private val WAKELOCK_PERMISSIONS = setOf(
        "android.permission.WAKE_LOCK",
        "android.permission.SCHEDULE_EXACT_ALARM",
        "android.permission.USE_EXACT_ALARM",
        "android.permission.RECEIVE_BOOT_COMPLETED",
    )

    /**
     * 对单个组件做分类。
     *
     * @param appPermissions 应用声明的权限，用于 WAKELOCK 判定。
     *        注意这是应用级别的，不是组件级别 —— 权限申请在 Manifest 顶层，
     *        没法精确到某个组件。所以只有在组件本身已经有其他可疑特征时才叠加这个标签，
     *        否则整个应用几百个组件会全被打上 WAKELOCK，等于没分类。
     */
    fun classify(
        c: ManifestScanner.StaticComponent,
        appPermissions: Set<String>,
    ): Result {
        val tags = mutableListOf<Behavior>()
        val actions = c.matchedActions

        if (ManifestScanner.ACTION_LAUNCHER in actions) tags += Behavior.LAUNCHER
        if (ManifestScanner.ACTION_DEEPLINK in actions) tags += Behavior.DEEPLINK

        if (c.isForegroundService) tags += Behavior.FOREGROUND_SERVICE

        if (actions.any { it in AUTO_START_ACTIONS }) tags += Behavior.AUTO_START
        if (actions.any { it in WAKEUP_ACTIONS }) tags += Behavior.WAKEUP_LISTENER

        val isPush = actions.any { it in PUSH_ACTIONS } ||
            PUSH_NAME_HINTS.any { c.className.contains(it) }
        if (isPush) tags += Behavior.PUSH_SERVICE

        if (c.type == ComponentType.PROVIDER && c.exported && c.grantUriPermissions) {
            tags += Behavior.DANGEROUS_PROVIDER
        }

        // 对外暴露且无权限保护。Provider 单独有 DANGEROUS_PROVIDER 标签，不重复打。
        if (c.exported && c.permission.isNullOrEmpty() &&
            c.type != ComponentType.PROVIDER &&
            Behavior.LAUNCHER !in tags && Behavior.DEEPLINK !in tags
        ) {
            tags += Behavior.EXPORTED_NO_PERM
        }

        if (c.isIsolatedProcess) tags += Behavior.ISOLATED_PROCESS
        if (c.directBootAware) tags += Behavior.DIRECT_BOOT

        // 唤醒锁：只在已经有其他可疑标签时才叠加，避免全员命中
        if (tags.isNotEmpty() && appPermissions.any { it in WAKELOCK_PERMISSIONS } &&
            (Behavior.AUTO_START in tags || Behavior.WAKEUP_LISTENER in tags ||
                Behavior.PUSH_SERVICE in tags)
        ) {
            tags += Behavior.WAKELOCK
        }

        return Result(
            className = c.className,
            behaviors = tags.distinct(),
            sdk = SdkIdentifier.identify(c.className),
        )
    }

    /** 批量分类整个应用 */
    fun classifyAll(manifest: ManifestScanner.AppManifest): Map<String, Result> =
        manifest.components.associate { c ->
            c.className to classify(c, manifest.permissions)
        }

    /**
     * 挑出「可以放心禁用」的候选。
     *
     * 门槛：safeToBlock 为真 且 静态风险 ≥ threshold。
     * 默认 60 —— 大致对应「推送服务」及以上，自启和高频唤醒都在里面，
     * 而单纯的独立进程（40）、深链这类不会被误伤。
     */
    fun pickSafeCandidates(
        manifest: ManifestScanner.AppManifest,
        threshold: Int = 60,
    ): List<Result> = classifyAll(manifest).values
        .filter { it.safeToBlock && it.staticRisk >= threshold }
        .sortedByDescending { it.staticRisk }

    /** 按行为标签统计，用于概览页展示「这个应用有 12 个自启组件」 */
    fun summarize(manifest: ManifestScanner.AppManifest): Map<Behavior, Int> {
        val counter = LinkedHashMap<Behavior, Int>()
        classifyAll(manifest).values.forEach { r ->
            r.behaviors.forEach { b -> counter[b] = (counter[b] ?: 0) + 1 }
        }
        return counter.entries.sortedByDescending { it.key.risk }
            .associate { it.key to it.value }
    }
}
