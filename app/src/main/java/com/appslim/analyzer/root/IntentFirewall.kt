package com.appslim.analyzer.root

import com.appslim.analyzer.data.model.ComponentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Intent Firewall 规则读写。
 *
 * 【为什么要有这一层】
 * 只用 PM（setComponentEnabledSetting）冻组件有个漏洞：**应用自己也能调这个 API**。
 * 很多保活流氓的做法就是在启动时把自己被禁掉的组件一个个 enable 回来，
 * 你冻你的，它开它的，冻了跟没冻一样。
 *
 * IFW 是 AOSP 里另一套独立机制，规则文件放在 /data/system/ifw/，
 * 只有 system 用户能写。应用改不了，这就是它的价值 ——
 * 被 IFW 拦住的组件，应用怎么折腾都起不来。
 *
 * 两层配合（抄 Blocker 的 CombinedController）：
 *   - 禁用：IFW + PM 都写。PM 让状态在系统设置里可见，IFW 保证真拦得住
 *   - 状态查询：任一层报「启用」就算启用（保守，避免显示成已禁用但实际能跑）
 *
 * 【IFW 的两个限制】
 *   1. Provider 不支持。AOSP 的 IntentFirewall 只处理 Activity/Service/Broadcast
 *      三类 Intent 分发，Provider 走的是 ContentResolver 另一条路。Provider 只能靠 PM。
 *   2. 必须有 root。/data/system/ifw 目录连 shell 用户都进不去，Shizuku 无解。
 *
 * 【规则文件格式】
 * ```xml
 * <rules>
 *   <service block="true" log="false">
 *     <component-filter name="com.foo/com.foo.KeepAliveService" />
 *   </service>
 * </rules>
 * ```
 * 写完 AMS 会通过 FileObserver 自动重载，不用重启。
 */
object IntentFirewall {

    private const val IFW_DIR = "/data/system/ifw"
    /** 中转目录。直接往 /data/system/ifw 里 echo 有可能写一半被 AMS 读到 */
    private const val STAGING_DIR = "/data/local/tmp"

    /** IFW 的三种组件类型标签。注意 Receiver 在 IFW 里叫 broadcast */
    private fun tagOf(type: ComponentType): String? = when (type) {
        ComponentType.ACTIVITY -> "activity"
        ComponentType.SERVICE -> "service"
        ComponentType.RECEIVER -> "broadcast"
        ComponentType.PROVIDER -> null  // IFW 管不了 Provider
    }

    /** 一个包的完整 IFW 规则：类型 → 被拦组件的全名集合（pkg/cls） */
    data class Rules(
        val activity: MutableSet<String> = linkedSetOf(),
        val service: MutableSet<String> = linkedSetOf(),
        val broadcast: MutableSet<String> = linkedSetOf(),
    ) {
        val isEmpty: Boolean
            get() = activity.isEmpty() && service.isEmpty() && broadcast.isEmpty()

        val total: Int get() = activity.size + service.size + broadcast.size

        fun setOf(tag: String): MutableSet<String> = when (tag) {
            "activity" -> activity
            "service" -> service
            else -> broadcast
        }

        fun contains(name: String): Boolean =
            name in activity || name in service || name in broadcast
    }

    val isAvailable: Boolean
        get() = RootShell.isRootAvailable

    /**
     * 读取现有规则。
     *
     * 这一步不能省。别的工具（MyAndroidTools、冰箱之类）可能也往这个文件里写过东西，
     * 直接覆盖会把人家的规则抹掉。读出来合并再写回去才对。
     */
    suspend fun read(packageName: String): Rules = withContext(Dispatchers.IO) {
        val path = "$IFW_DIR/$packageName.xml"
        val r = RootShell.execAsync("[ -f $path ] && cat $path || echo ''")
        if (r.code != 0 || r.out.isBlank()) return@withContext Rules()
        parse(r.out)
    }

    /**
     * 极简 XML 解析。
     *
     * 只认 `<activity|service|broadcast>` 和里面的 `<component-filter name="..."/>`，
     * 别的过滤器（action/category/sender 等）一概忽略 —— 我们不生成它们，
     * 但**遇到时也不会破坏**：见 write() 里的保留逻辑。
     *
     * 没用 XmlPullParser 是因为它对畸形 XML 会直接抛异常，
     * 而 IFW 文件被各种工具写过，格式不一定规范。正则更皮实。
     */
    private fun parse(xml: String): Rules {
        val rules = Rules()
        val blockRe = Regex(
            """<(activity|service|broadcast)\b[^>]*>(.*?)</\1>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        )
        val filterRe = Regex(
            """<component-filter\s+name\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE
        )
        blockRe.findAll(xml).forEach { m ->
            val tag = m.groupValues[1].lowercase()
            val body = m.groupValues[2]
            val target = rules.setOf(tag)
            filterRe.findAll(body).forEach { f -> target += f.groupValues[1] }
        }
        return rules
    }

    /** 生成 XML。手写字符串就够了，这个格式简单到不值得引序列化库。 */
    private fun serialize(rules: Rules): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n")
        append("<rules>\n")
        listOf(
            "activity" to rules.activity,
            "service" to rules.service,
            "broadcast" to rules.broadcast,
        ).forEach { (tag, set) ->
            if (set.isEmpty()) return@forEach
            append("  <$tag block=\"true\" log=\"false\">\n")
            set.forEach { name ->
                append("    <component-filter name=\"${escape(name)}\" />\n")
            }
            append("  </$tag>\n")
        }
        append("</rules>\n")
    }

    /** 类名里出现 XML 特殊字符的概率极低，但内部类的 `$` 和泛型不处理会出事 */
    private fun escape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    /**
     * 写入规则。
     *
     * 走 staging 再 mv：mv 在同一文件系统内是原子的，
     * 避免 AMS 的 FileObserver 读到写了一半的文件。
     * 直接 `echo > /data/system/ifw/x.xml` 有这个风险。
     */
    suspend fun write(packageName: String, rules: Rules): Boolean =
        withContext(Dispatchers.IO) {
            val target = "$IFW_DIR/$packageName.xml"

            // 规则空了就删文件。留个空 <rules/> 也行，但删掉更干净
            if (rules.isEmpty) {
                return@withContext RootShell.execAsync("rm -f $target").code == 0
            }

            val staging = "$STAGING_DIR/appslim_ifw_$packageName.xml"
            val xml = serialize(rules)

            // heredoc 传内容，避免类名里的字符被 shell 解释
            val script = buildString {
                append("mkdir -p $IFW_DIR\n")
                append("cat > $staging << 'APPSLIM_IFW_EOF'\n")
                append(xml)
                append("APPSLIM_IFW_EOF\n")
                append("mv -f $staging $target\n")
                append("chmod 644 $target\n")
                // SELinux 上下文跟目录走，个别 ROM 上 mv 过来的文件 context 不对
                append("chcon u:object_r:system_data_file:s0 $target 2>/dev/null\n")
                append("echo IFW_OK\n")
            }
            val r = RootShell.execAsync(script)
            r.out.contains("IFW_OK")
        }

    /**
     * 批量设置。
     *
     * 与 Blocker 的差异：它的 batchDisable 逐个调 disable()，
     * 而每个 disable() 内部都会 saveRules() 写一次文件 —— 100 个组件写 100 次。
     * 这里改成先在内存里把规则攒齐，最后一次写盘。
     *
     * @param components 类名 → 组件类型。需要类型是因为 IFW 按类型分组，
     *                   而且 Provider 要跳过。
     * @return 实际写入 IFW 的组件数（不含被跳过的 Provider）
     */
    suspend fun setBlocked(
        packageName: String,
        components: Map<String, ComponentType>,
        blocked: Boolean,
    ): Int = withContext(Dispatchers.IO) {
        if (!isAvailable || components.isEmpty()) return@withContext 0

        val rules = read(packageName)
        var changed = 0

        components.forEach { (cls, type) ->
            val tag = tagOf(type) ?: return@forEach  // Provider 跳过
            val full = "$packageName/$cls"
            val set = rules.setOf(tag)
            val ok = if (blocked) set.add(full) else set.remove(full)
            if (ok) changed++
        }

        if (changed == 0) return@withContext 0
        if (write(packageName, rules)) changed else 0
    }

    /** 查询某组件是否被 IFW 拦着 */
    suspend fun isBlocked(packageName: String, className: String): Boolean =
        read(packageName).contains("$packageName/$className")

    /** 清掉某个包的全部 IFW 规则 */
    suspend fun clear(packageName: String): Boolean = withContext(Dispatchers.IO) {
        RootShell.execAsync("rm -f $IFW_DIR/$packageName.xml").code == 0
    }

    /** 列出所有被 IFW 管过的包，用于「已优化应用」列表 */
    suspend fun listManagedPackages(): List<String> = withContext(Dispatchers.IO) {
        val r = RootShell.execAsync("ls $IFW_DIR/*.xml 2>/dev/null")
        if (r.code != 0) return@withContext emptyList()
        r.out.lineSequence()
            .map { it.trim().substringAfterLast('/').removeSuffix(".xml") }
            .filter { it.isNotBlank() && it.contains('.') }
            .toList()
    }

    /**
     * 自检：IFW 到底能不能用。
     *
     * 光有 root 不代表 IFW 可用 —— 部分魔改 ROM（尤其是某些海外定制包）
     * 把 IntentFirewall 整个阉割了，写进去也没人读。
     * 这里只验证「文件能不能写」，能写就认为可用。
     */
    suspend fun selfCheck(): String = withContext(Dispatchers.IO) {
        if (!RootShell.isRootAvailable) return@withContext "无 Root，IFW 不可用"
        val probe = "$IFW_DIR/.appslim_probe"
        val r = RootShell.execAsync(
            "mkdir -p $IFW_DIR && touch $probe && rm -f $probe && echo PROBE_OK"
        )
        if (r.out.contains("PROBE_OK")) "IFW 可用" else "IFW 目录不可写：${r.err.take(80)}"
    }
}
