package com.appslim.analyzer.root

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import com.appslim.analyzer.data.model.ComponentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * 组件启停控制器。
 *
 * 【为什么不直接 pm disable】
 * 最直观的做法是 `su -c "pm disable pkg/comp"`。能用，但慢得离谱：
 * 每执行一条就要 fork 一个 shell 进程 + 起一遍 pm 这个 Java 命令行工具，
 * 单条开销 300~500ms。中国移动这种应用一次要冻 100 多个组件，
 * 光等就要 40~60 秒，中间还不能退出界面。
 *
 * Blocker（lihenggui/blocker）的做法要聪明得多：`pm` 命令自己不过是
 * 拿到 IPackageManager 这个 Binder 然后调 setComponentEnabledSetting()。
 * 那就跳过中间商 —— 直接拿 Binder 自己调。一次 Binder 事务几毫秒，
 * 121 个组件一秒内跑完。
 *
 * 【为什么用反射】
 * IPackageManager 是 @hide 的，正常编译期拿不到。Blocker 引了一整套
 * hidden-api stub（dev.rikka.tools.refine + framework stub jar）来解决。
 * 那套东西会让构建复杂不少，而我们只需要调一个方法，用反射更划算 ——
 * 代价是失去编译期检查，所以下面每个反射点都做了降级兜底。
 *
 * 【三条通道】
 *   1. Shizuku Binder 直连 —— 最快，非 root 可用，优先
 *   2. Root Binder（暂未实现，需要常驻 root 进程持有 Binder）
 *   3. shell pm disable —— 最慢但最通用，兜底
 *
 * 【为什么还要叠一层 IFW】
 * PM 这条路有个漏洞：setComponentEnabledSetting 应用自己也能调。
 * 保活流氓的标准操作就是启动时把被禁的组件一个个 enable 回来 ——
 * 你冻你的，它开它的，等于白冻。IFW 规则写在 /data/system/ifw/，
 * 应用改不了，所以有 root 时两层都写。详见 [IntentFirewall]。
 */
object ComponentController {

    /** setComponentEnabledSetting 的状态常量，与 PackageManager 里的一致 */
    private const val STATE_DEFAULT = PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
    private const val STATE_ENABLED = PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    private const val STATE_DISABLED = PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    enum class Channel(val label: String) {
        SHIZUKU_BINDER("Shizuku 直连"),
        ROOT_SHELL("Root Shell"),
        NONE("不可用"),
    }

    data class BatchResult(
        val channel: Channel,
        val total: Int,
        val succeeded: Int,
        val elapsedMs: Long,
        val failures: List<String> = emptyList(),
        /** 同时写入 IFW 的组件数。0 表示没走 IFW（无 root 或全是 Provider） */
        val ifwCount: Int = 0,
    ) {
        val allOk: Boolean get() = succeeded == total
        /** 平均每个组件耗时，用来直观对比通道差距 */
        val perItemMs: Long get() = if (total == 0) 0 else elapsedMs / total
        /** 双层生效 = 应用自己改不回来 */
        val dualLayer: Boolean get() = ifwCount > 0

        fun describe(): String = buildString {
            append("${channel.label}：$succeeded/$total 成功")
            if (ifwCount > 0) append("，其中 $ifwCount 个已写入防火墙")
            append("，耗时 ${elapsedMs}ms")
            if (total > 0) append("（平均 ${perItemMs}ms/个）")
        }
    }

    // ---- Binder 反射缓存。这几个 Method 一次解析，反复用 ----

    private data class PmHandle(
        val proxy: Any,
        val setEnabled: java.lang.reflect.Method,
        /** Android 14 起多了一个 callingPackage 参数 */
        val hasCallingPackage: Boolean,
    )

    @Volatile
    private var cachedPm: PmHandle? = null

    @Volatile
    private var pmResolveFailed = false

    /**
     * 拿 IPackageManager 代理。
     *
     * 步骤等价于 `IPackageManager.Stub.asInterface(ShizukuBinderWrapper(binder))`，
     * 只是全部换成反射。ShizukuBinderWrapper 那层包装是必需的 ——
     * 它会把事务转发给 Shizuku 服务进程，让调用以 shell/root 身份执行。
     * 少了这层，setComponentEnabledSetting 会因为权限不足直接抛 SecurityException。
     */
    private fun obtainPackageManager(): PmHandle? {
        cachedPm?.let { return it }
        if (pmResolveFailed || !ShizukuBridge.hasPermission) return null

        return runCatching {
            val raw: IBinder = SystemServiceHelper.getSystemService("package")
                ?: error("拿不到 package 系统服务")
            val wrapped = ShizukuBinderWrapper(raw)

            val stub = Class.forName("android.content.pm.IPackageManager\$Stub")
            val asInterface = stub.getMethod("asInterface", IBinder::class.java)
            val proxy = asInterface.invoke(null, wrapped)
                ?: error("asInterface 返回 null")

            val iface = Class.forName("android.content.pm.IPackageManager")

            // Android 14(34) 起签名多了 callingPackage。先按新签名找，找不到退回旧的。
            var hasCallingPackage = true
            val method = runCatching {
                iface.getMethod(
                    "setComponentEnabledSetting",
                    ComponentName::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    String::class.java,
                )
            }.getOrElse {
                hasCallingPackage = false
                iface.getMethod(
                    "setComponentEnabledSetting",
                    ComponentName::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            }

            PmHandle(proxy, method, hasCallingPackage).also { cachedPm = it }
        }.getOrElse {
            // 解析失败就别反复试了，直接标记降级走 shell
            pmResolveFailed = true
            null
        }
    }

    /** 当前用户 ID。多用户/工作资料下不是 0，得实际算。 */
    private fun userIdOf(context: Context): Int = runCatching {
        val uid = context.packageManager
            .getApplicationInfo(context.packageName, 0).uid
        uid / 100000  // PER_USER_RANGE
    }.getOrDefault(0)

    /** 当前可用的最快通道 */
    fun availableChannel(): Channel = when {
        ShizukuBridge.hasPermission && obtainPackageManager() != null -> Channel.SHIZUKU_BINDER
        RootShell.isRootAvailable -> Channel.ROOT_SHELL
        else -> Channel.NONE
    }

    /** 双层拦截是否可用。要 root —— /data/system/ifw 连 shell 用户都进不去 */
    val dualLayerAvailable: Boolean
        get() = IntentFirewall.isAvailable

    /**
     * 批量设置组件启停状态。
     *
     * 有 root 时会同时写 IFW，两层都拦。传了 [types] 才能写 IFW ——
     * 因为 IFW 规则按组件类型分组，而且 Provider 得跳过（IFW 不支持）。
     * 不传就退化成纯 PM 模式，功能不受影响，只是应用可能自己恢复。
     *
     * @param components 组件全类名列表
     * @param enabled true=恢复 false=禁用
     * @param types 类名 → 组件类型。缺省则跳过 IFW 层
     * @param onProgress 进度回调（已完成数, 总数），用于 UI 进度条
     */
    suspend fun setEnabled(
        context: Context,
        packageName: String,
        components: List<String>,
        enabled: Boolean,
        types: Map<String, ComponentType>? = null,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): BatchResult = withContext(Dispatchers.IO) {
        if (components.isEmpty()) {
            return@withContext BatchResult(Channel.NONE, 0, 0, 0)
        }
        val start = System.currentTimeMillis()

        // ---- 第一层：IFW。先写它，因为它才是真拦得住的那层 ----
        // 顺序有讲究：禁用时先上 IFW 再改 PM，中间窗口期组件已经被拦；
        // 反过来的话 PM 改完到 IFW 写完之间应用有机会把自己 enable 回来。
        val ifwCount = if (types != null && IntentFirewall.isAvailable) {
            val subset = components.mapNotNull { cls ->
                types[cls]?.let { cls to it }
            }.toMap()
            runCatching {
                IntentFirewall.setBlocked(packageName, subset, blocked = !enabled)
            }.getOrDefault(0)
        } else 0

        // ---- 第二层：PM ----
        obtainPackageManager()?.let { pm ->
            val r = viaBinder(context, pm, packageName, components, enabled, onProgress)
            // Binder 全挂说明通道有问题（比如 Shizuku 掉线），降级重试一次
            if (r.succeeded > 0 || !RootShell.isRootAvailable) {
                return@withContext r.copy(
                    elapsedMs = System.currentTimeMillis() - start,
                    ifwCount = ifwCount,
                )
            }
            cachedPm = null
        }

        if (RootShell.isRootAvailable) {
            return@withContext viaShell(packageName, components, enabled, onProgress)
                .copy(
                    elapsedMs = System.currentTimeMillis() - start,
                    ifwCount = ifwCount,
                )
        }

        BatchResult(Channel.NONE, components.size, 0, System.currentTimeMillis() - start,
            listOf("没有可用的执行通道，请先授予 Root 或 Shizuku 权限"))
    }

    /** 通道一：Binder 直连，一个组件一次事务 */
    private fun viaBinder(
        context: Context,
        pm: PmHandle,
        packageName: String,
        components: List<String>,
        enabled: Boolean,
        onProgress: ((Int, Int) -> Unit)?,
    ): BatchResult {
        val state = if (enabled) STATE_ENABLED else STATE_DISABLED
        val userId = userIdOf(context)
        val failures = mutableListOf<String>()
        var done = 0

        components.forEachIndexed { i, cls ->
            val ok = runCatching {
                val cn = ComponentName(packageName, cls)
                // flags 传 0 = 允许杀掉应用进程让设置立即生效。
                // 传 DONT_KILL_APP 的话组件状态改了但进程还活着，
                // 已经跑起来的 Service 不会停，等于没冻。
                if (pm.hasCallingPackage) {
                    pm.setEnabled.invoke(
                        pm.proxy, cn, state, 0, userId, context.packageName
                    )
                } else {
                    pm.setEnabled.invoke(pm.proxy, cn, state, 0, userId)
                }
            }.isSuccess
            if (ok) done++ else failures += cls
            onProgress?.invoke(i + 1, components.size)
        }

        return BatchResult(
            channel = Channel.SHIZUKU_BINDER,
            total = components.size,
            succeeded = done,
            elapsedMs = 0,
            failures = failures,
        )
    }

    /**
     * 通道二：shell 兜底。
     *
     * 关键优化：所有命令拼成一条 su 会话执行，而不是每条起一个 su。
     * 100 条命令从「100 次 fork」降到「1 次 fork」，
     * 虽然还是比 Binder 慢一个数量级，但至少从分钟级回到十几秒。
     */
    private suspend fun viaShell(
        packageName: String,
        components: List<String>,
        enabled: Boolean,
        onProgress: ((Int, Int) -> Unit)?,
    ): BatchResult {
        val verb = if (enabled) "enable" else "disable-user --user 0"
        val script = components.joinToString("\n") { "pm $verb $packageName/$it" }
        val r = RootShell.execAsync(script)
        onProgress?.invoke(components.size, components.size)

        // pm 成功时输出「new state: disabled-user」，据此数成功条数
        val okCount = r.out.lineSequence().count { it.contains("new state:") }
        return BatchResult(
            channel = Channel.ROOT_SHELL,
            total = components.size,
            succeeded = if (okCount > 0) okCount else if (r.code == 0) components.size else 0,
            elapsedMs = 0,
            failures = if (r.err.isBlank()) emptyList() else listOf(r.err),
        )
    }

    /**
     * 查询组件当前是否启用。
     *
     * 用本地 PackageManager 就够，不需要提权 —— 读状态是公开操作。
     */
    fun isEnabled(context: Context, packageName: String, className: String): Boolean =
        runCatching {
            when (context.packageManager.getComponentEnabledSetting(
                ComponentName(packageName, className)
            )) {
                STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> false
                STATE_ENABLED -> true
                else -> true  // DEFAULT：跟随 Manifest，绝大多数是启用
            }
        }.getOrDefault(true)

    /**
     * 双层状态查询：任一层拦住就算被禁。
     *
     * 抄 Blocker 的保守策略（它用 `ifwEnabled || pmEnabled` 判启用，
     * 等价于这里的「任一层拦住即禁用」）。宁可显示成「已禁用」实际能跑，
     * 也不能显示「已启用」结果用户点进去发现根本调不起来。
     *
     * 注意这个方法要读文件，别在列表滚动里逐项调 —— 用 [blockedSetOf] 批量查。
     */
    suspend fun isEnabledDual(
        context: Context,
        packageName: String,
        className: String,
    ): Boolean = withContext(Dispatchers.IO) {
        if (!isEnabled(context, packageName, className)) return@withContext false
        if (!IntentFirewall.isAvailable) return@withContext true
        !IntentFirewall.isBlocked(packageName, className)
    }

    /**
     * 批量查被拦组件。一次读文件 + 一轮 PM 查询，比逐个调快得多。
     *
     * @return 处于「被禁用」状态的类名集合
     */
    suspend fun blockedSetOf(
        context: Context,
        packageName: String,
        components: List<String>,
    ): Set<String> = withContext(Dispatchers.IO) {
        val result = mutableSetOf<String>()
        components.forEach { cls ->
            if (!isEnabled(context, packageName, cls)) result += cls
        }
        if (IntentFirewall.isAvailable) {
            runCatching {
                val rules = IntentFirewall.read(packageName)
                components.forEach { cls ->
                    if (rules.contains("$packageName/$cls")) result += cls
                }
            }
        }
        result
    }

    /** 一键恢复：把之前禁用的全放回去，两层一起清 */
    suspend fun restoreAll(
        context: Context,
        packageName: String,
        components: List<String>,
        types: Map<String, ComponentType>? = null,
    ): BatchResult = setEnabled(context, packageName, components, true, types)

    /**
     * 彻底恢复某个包：清空 IFW 规则文件 + PM 状态全部复位。
     *
     * 与 restoreAll 的区别：这个不需要知道当初冻了哪些，直接把文件删了。
     * 用于「我也不记得动过什么了，全恢复原样」这种场景。
     */
    suspend fun resetPackage(
        context: Context,
        packageName: String,
        allComponents: List<String>,
    ): BatchResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val ifwOk = if (IntentFirewall.isAvailable) {
            runCatching { IntentFirewall.clear(packageName) }.getOrDefault(false)
        } else false

        val r = setEnabled(context, packageName, allComponents, enabled = true)
        r.copy(
            elapsedMs = System.currentTimeMillis() - start,
            ifwCount = if (ifwOk) allComponents.size else 0,
        )
    }

    /** Android 版本相关的能力说明，展示在设置页 */
    fun channelHint(): String = buildString {
        append(
            when (availableChannel()) {
                Channel.SHIZUKU_BINDER ->
                    "Shizuku 直连可用，批量操作预计每个组件 5ms 以内"
                Channel.ROOT_SHELL ->
                    "仅 Root Shell 可用，批量操作较慢。装 Shizuku 可提速数十倍"
                Channel.NONE ->
                    "未授权。需要 Root 或 Shizuku 才能禁用组件" +
                        if (Build.VERSION.SDK_INT >= 34) "（Android 14+ 推荐 Shizuku）" else ""
            }
        )
        append("\n")
        append(
            if (dualLayerAvailable) {
                "防火墙层可用：冻结后应用无法自行恢复"
            } else {
                "防火墙层不可用（需 Root）：应用可能自行恢复被冻组件"
            }
        )
    }
}
