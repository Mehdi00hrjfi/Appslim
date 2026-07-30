package com.appslim.analyzer.ui

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appslim.analyzer.analysis.RiskScorer
import com.appslim.analyzer.analysis.SdkIdentifier
import com.appslim.analyzer.data.model.BlockRules
import com.appslim.analyzer.data.model.ComponentMetric
import com.appslim.analyzer.data.model.ComponentType
import com.appslim.analyzer.data.model.RiskTier
import com.appslim.analyzer.monitor.ManifestScanner
import com.appslim.analyzer.monitor.ProcessSampler
import com.appslim.analyzer.monitor.RuntimeMonitor
import com.appslim.analyzer.root.RootShell
import com.appslim.analyzer.root.ShizukuBridge
import com.appslim.analyzer.rules.RulesGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * 主 ViewModel：串联 采集层 → 分析引擎 → 输出（报告 §3 总体架构）
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val scanner = ManifestScanner(app)
    private val runtime = RuntimeMonitor(app)
    private val sampler = ProcessSampler(app)

    /** 权限层状态（Root / Shizuku / LSPosed，报告 §3 权限层） */
    data class PermStatus(val root: Boolean, val shizuku: Boolean)
    val perms = MutableStateFlow(PermStatus(root = false, shizuku = false))

    /** 应用列表权限状态（使用情况访问，用于扫描组件运行时行为） */
    val usageGranted = MutableStateFlow(false)

    /** 应用搜索关键字（应用列表页搜索框） */
    val search = MutableStateFlow("")

    /** 应用列表筛选（全部 / 仅应用 / 仅系统） */
    enum class AppFilter { ALL, USER, SYSTEM }
    val appFilter = MutableStateFlow(AppFilter.ALL)

    /** 应用列表（仅基础信息，启动即加载，不做组件扫描） */
    data class AppItem(
        val pkg: String,
        val label: String,
        val version: String,
        val icon: Bitmap? = null,
        val isSystem: Boolean
    )
    val apps = MutableStateFlow<List<AppItem>>(emptyList())

    /** 过滤后的应用列表（StateFlow，确保筛选/搜索变化立即触发重组） */
    val filteredApps = kotlinx.coroutines.flow.combine(
        apps, appFilter, search
    ) { list, filter, query ->
        val q = query.trim().lowercase()
        list.filter { item ->
            val matchFilter = when (filter) {
                AppFilter.ALL -> true
                AppFilter.USER -> !item.isSystem
                AppFilter.SYSTEM -> item.isSystem
            }
            val matchQuery = q.isEmpty() ||
                    item.label.lowercase().contains(q) ||
                    item.pkg.lowercase().contains(q)
            matchFilter && matchQuery
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    /** 已扫描过的组件缓存（按包名）：首次点击某应用才扫描，之后复用，不重复扫 */
    private val appCache = MutableStateFlow<Map<String, ManifestScanner.AppManifest>>(emptyMap())

    /** 某应用已扫描出的组件数（未扫描返回 null） */
    fun compCountOf(pkg: String): Int? = appCache.value[pkg]?.components?.size

    /** 软件进程（Dashboard 「软件进程」卡片展示：图标、名称、资源占用百分比） */
    data class ProcessItem(
        val pid: Int,
        val pkg: String,
        val label: String,
        val usagePercent: Float,
        val memoryMb: Int,
        val icon: Bitmap? = null
    )
    val processes = MutableStateFlow<List<ProcessItem>>(emptyList())

    /** 系统整体 CPU 占用（0–100），由 /proc/stat 计算 */
    val systemCpuPercent = MutableStateFlow(0f)

    /** 当前选中的进程（点击后展示详情 / Kill） */
    val selectedProcess = MutableStateFlow<ProcessItem?>(null)
    val processDetail = MutableStateFlow<Pair<String, String>?>(null)

    /** 当前选中应用的运行时画像（分析引擎输入） */
    val selectedPkg = MutableStateFlow<String?>(null)
    val metrics = MutableStateFlow<List<ComponentMetric>>(emptyList())
    val sdks = MutableStateFlow<List<SdkIdentifier.SdkInfo>>(emptyList())

    /** 阻断名单（内存态；持久化于 Room RuleSetEntity） */
    val blocked = MutableStateFlow<Set<String>>(emptySet())

    /** 是否正在扫描某应用的组件（首次点击才扫，之后走缓存） */
    val isScanning = MutableStateFlow(false)

    /** 生成的 rules.json 文本（Rules 页预览） */
    val rulesJson = MutableStateFlow("")

    init {
        refreshPermissions()
        scanInstalled()
        startProcessPolling()
    }

    fun refreshPermissions() {
        val app = getApplication<Application>()
        val root = RootShell.isRootAvailable
        perms.value = PermStatus(
            root = root,
            shizuku = ShizukuBridge.hasPermission
        )
        // 有 Root 时无需额外申请「使用情况访问」即可监控/读取全部进程
        usageGranted.value = PermissionHelper.hasUsageAccess(app) || root
    }

    /** 跳转系统设置页授予「应用列表权限（使用情况访问）」 */
    fun requestUsagePermission() = PermissionHelper.openUsageSettings(getApplication())


    /** 启动即加载全部已安装应用（仅基础信息 + 图标，不做组件扫描） */
    fun scanInstalled() = viewModelScope.launch(Dispatchers.IO) {
        val pm = getApplication<Application>().packageManager
        apps.value = runCatching {
            pm.getInstalledPackages(0)
                .mapNotNull { pi ->
                    val ai = pi.applicationInfo ?: return@mapNotNull null
                    if (ai.packageName == getApplication<Application>().packageName) return@mapNotNull null
                    val label = runCatching { pm.getApplicationLabel(ai).toString() }.getOrNull() ?: ai.packageName
                    val icon = runCatching { ai.loadIcon(pm) }.getOrNull()?.toBitmap()
                    AppItem(
                        pkg = ai.packageName,
                        label = label,
                        version = pi.versionName ?: "-",
                        icon = icon,
                        isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    )
                }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
    }

    /**
     * 启动真实进程采样协程：
     * - 进程列表：每 ~500ms 单次快照，首帧 CPU=0 立即上屏，下一帧补齐；
     * - 系统 CPU：独立协程循环，不再串行排在进程采样后面（避免相互拖慢）；
     * - 未拿到 Root 时周期性（约 5s）重新检测一次，使首页在用户授权 Root 后及时刷新状态。
     */
    private fun startProcessPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            var iter = 0
            while (true) {
                refreshProcesses()
                if (!perms.value.root && ++iter % 10 == 0) refreshPermissions()
                delay(500L)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                systemCpuPercent.value = sampler.sampleSystemCpu(500)
            }
        }
    }

    /** 读取真实运行进程并更新 processes（含图标、CPU%、内存） */
    private suspend fun refreshProcesses() {
        val hasRoot = perms.value.root
        val list = sampler.sample(hasRoot = hasRoot)
            .map { ProcessItem(it.pid, it.pkg, it.label, it.cpuPercent, it.memoryMb, it.icon) }
            .sortedByDescending { it.usagePercent }
            .take(12)
        processes.value = list
    }

    /** 选中进程并拉取 Activity/Service 详情 */
    fun selectProcess(item: ProcessItem) = viewModelScope.launch(Dispatchers.IO) {
        selectedProcess.value = item
        processDetail.value = sampler.details(item.pkg)
    }

    /** 结束选中进程 */
    fun killProcess(item: ProcessItem) = viewModelScope.launch(Dispatchers.IO) {
        sampler.kill(item.pkg)
        // 刷新一次，让列表立刻反映进程变化
        refreshProcesses()
    }

    /** 选中应用：复用已扫描缓存，未扫描过才扫描该单个应用，避免每次重扫 */
    fun selectApp(pkg: String) = viewModelScope.launch(Dispatchers.IO) {
        selectedPkg.value = pkg
        val cached = appCache.value[pkg]
        val manifest = if (cached != null) {
            cached
        } else {
            isScanning.value = true
            scanner.scan(pkg)?.also { appCache.value = appCache.value + (pkg to it) }
                .also { isScanning.value = false }
        }
        if (manifest == null) {
            metrics.value = emptyList()
            sdks.value = emptyList()
            return@launch
        }
        metrics.value = buildMetrics(manifest)
        sdks.value = SdkIdentifier.identifyAll(manifest.components.map { it.className })
    }

    private fun buildMetrics(manifest: ManifestScanner.AppManifest): List<ComponentMetric> {
        return manifest.components.map { c ->
            ComponentMetric(
                packageName = manifest.packageName,
                className = c.className,
                type = c.type,
                description = SdkIdentifier.identify(c.className)?.name ?: "",
                // 运行时指标：由采集层聚合填充；静态阶段给保守默认
                coreAffinity = if (c.exported) 0.4f else 0.6f
            )
        }
    }

    fun riskOf(m: ComponentMetric) = RiskScorer.score(m)
    fun tierOf(m: ComponentMetric) = RiskScorer.tier(riskOf(m).total)

    fun toggleBlock(m: ComponentMetric) {
        blocked.value = if (m.className in blocked.value) blocked.value - m.className
                        else blocked.value + m.className
        rebuildRulesJson()
    }

    /** 一键优化：对所有已扫描（缓存）的应用自动阻断中高风险组件 */
    fun quickOptimize(): Int {
        val allBlocked = mutableSetOf<String>()
        appCache.value.values.forEach { manifest ->
            allBlocked += RiskScorer.pickBlockCandidates(buildMetrics(manifest))
                .map { it.className }
        }
        blocked.value = allBlocked
        rebuildRulesJson()
        return allBlocked.size
    }

    /** 被阻断的组件按包名分组（用于打包/报告） */
    fun blockedByPackage(): Map<String, List<String>> {
        val set = blocked.value
        return appCache.value.values.mapNotNull { manifest ->
            val list = manifest.components.filter { it.className in set }.map { it.className }
            if (list.isEmpty()) null else manifest.packageName to list
        }.toMap()
    }

    private fun rebuildRulesJson() {
        val pkg = selectedPkg.value ?: return
        val byType = { t: ComponentType ->
            metrics.value.filter { it.type == t && it.className in blocked.value }.map { it.className }.sorted()
        }
        rulesJson.value = RulesGenerator.toJson(
            BlockRules(
                targetPackage = pkg,
                blockActivity = byType(ComponentType.ACTIVITY),
                blockService = byType(ComponentType.SERVICE),
                blockProvider = byType(ComponentType.PROVIDER),
                blockReceiver = byType(ComponentType.RECEIVER)
            )
        )
    }

    /** 打包 AppSlim_Module.zip（报告 §10），返回输出文件 */
    fun packModule(): File? {
        val byPkg = blockedByPackage()
        if (byPkg.isEmpty()) return null
        val out = File(getApplication<Application>().getExternalFilesDir(null), "AppSlim_Module.zip")
        return RulesGenerator.packModules(byPkg, out)
    }

    /** 性能对比估算（报告 §12.4）：按阻断名单估计资源释放量 */
    fun estimatedSavings(): Pair<Int, Int> {
        var cpu = 0
        var ram = 0
        metrics.value.filter { it.className in blocked.value }.forEach {
            cpu += it.cpuPeak.toInt()
            ram += (it.cpuPeak * 5).toInt() // 简化估算模型
        }
        return cpu to ram
    }

    /** 高风险组件数（总览页） */
    fun highRiskCount(): Int =
        metrics.value.count { tierOf(it) == RiskTier.BLOCK || tierOf(it) == RiskTier.HIGH }
}
