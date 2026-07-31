package com.appslim.analyzer.ui

import android.app.Application
import android.app.usage.UsageStatsManager
import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import com.appslim.analyzer.analysis.BehaviorClassifier
import com.appslim.analyzer.analysis.RiskScorer
import com.appslim.analyzer.analysis.SdkIdentifier
import com.appslim.analyzer.data.model.BlockRules
import com.appslim.analyzer.data.model.ComponentMetric
import com.appslim.analyzer.data.model.ComponentType
import com.appslim.analyzer.data.model.RiskTier
import com.appslim.analyzer.data.repo.AppRepository
import com.appslim.analyzer.monitor.ManifestScanner
import com.appslim.analyzer.monitor.ProcessSampler
import com.appslim.analyzer.monitor.RuntimeMonitor
import com.appslim.analyzer.root.ComponentController
import com.appslim.analyzer.root.RootShell
import com.appslim.analyzer.root.ShizukuBridge
import com.appslim.analyzer.rules.RulesGenerator
import com.appslim.analyzer.ui.IconCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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

    /** 应用是否处于前台（进程级生命周期）；后台时自动暂停/降频所有采集 */
    val appForeground = MutableStateFlow(true)

    /** 应用搜索关键字（应用列表页搜索框） */
    val search = MutableStateFlow("")

    /** 应用列表筛选（全部 / 仅应用 / 仅系统） */
    enum class AppFilter { ALL, USER, SYSTEM }
    val appFilter = MutableStateFlow(AppFilter.ALL)

    /** 应用列表排序（名称 / 可瘦身优先 / 最近更新 / 最近使用） */
    enum class AppSort { NAME, SLIM, UPDATED, RECENT }
    val appSort = MutableStateFlow(AppSort.NAME)

    /**
     * 应用列表单元：**不含图标**（图标由卡片异步从 IconCache 取，避免列表流里塞上百个 Bitmap）。
     * 计数与时间字段由后台富化（AppRepository.enrich）填充，首帧可能为零，富化后自动刷新。
     */
    data class AppItem(
        val pkg: String,
        val label: String,
        val version: String,
        val isSystem: Boolean,
        val apkPath: String,
        val activityCount: Int,
        val serviceCount: Int,
        val receiverCount: Int,
        val providerCount: Int,
        val installTime: Long,
        val updateTime: Long
    ) {
        val compCount: Int get() = activityCount + serviceCount + receiverCount + providerCount
        /** 后台组件（服务 + 接收器）＝ 最该被瘦身、最常偷跑的指标 */
        val backgroundCount: Int get() = serviceCount + receiverCount
    }

    /** 快列表（未排序的主数据，排序在 filteredApps 里做） */
    val apps = MutableStateFlow<List<AppItem>>(emptyList())

    /** 后台富化得到的组件计数（按包名）；卡片读它显示「N 组件」徽标，随富化进度增量更新 */
    private val compMeta = MutableStateFlow<Map<String, AppRepository.Meta>>(emptyMap())
    fun metaOf(pkg: String): AppRepository.Meta? = compMeta.value[pkg]

    /** 各应用最近一次启动时间（来自 UsageStats，仅在使用情况访问授权后可用） */
    private val lastLaunch = MutableStateFlow<Map<String, Long>>(emptyMap())

    /** 过滤 + 搜索 + 排序后的应用列表 */
    val filteredApps = kotlinx.coroutines.flow.combine(
        apps, appFilter, search, appSort, lastLaunch
    ) { list, filter, query, sort, launchMap ->
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
        }.let { filtered ->
            when (sort) {
                AppSort.NAME -> filtered.sortedBy { it.label.lowercase() }
                // 可瘦身优先：后台组件权重最高，其次总组件数
                AppSort.SLIM -> filtered.sortedByDescending { it.backgroundCount * 1000 + it.compCount }
                AppSort.UPDATED -> filtered.sortedByDescending { it.updateTime }
                AppSort.RECENT -> filtered.sortedByDescending { launchMap[it.pkg] ?: 0L }
            }
        }
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), emptyList())

    /** 已扫描过的组件缓存（按包名）：首次点击某应用才扫描，之后复用，不重复扫 */
    private val appCache = MutableStateFlow<Map<String, ManifestScanner.AppManifest>>(emptyMap())

    /** 某应用已富化出的组件总数（列表徽标用；未富化返回 null） */
    fun compCountOf(pkg: String): Int? = compMeta.value[pkg]?.compCount

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
        refreshAppList()
        observeAppVisibility()
        startProcessPolling()
    }

    /**
     * 监听进程级前后台状态（ProcessLifecycleOwner）：
     * 应用进入后台即置 appForeground=false，所有采集循环会据此暂停/降频，
     * 从而退出后台后不再持续占用 CPU 核心。
     */
    private fun observeAppVisibility() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) { appForeground.value = true }
            override fun onStop(owner: LifecycleOwner) { appForeground.value = false }
        })
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


    /**
     * 刷新应用列表（MT Manager 式两阶段）：
     *
     * 阶段一（即时，~100–300ms）：getInstalledApplications(0) 出快列表，计数先读 Room 缓存，
     *   命中即显示，未命中先显示「点击扫描」，列表立刻可见、可滚动。
     * 阶段二（后台）：仅对「缓存缺失 / APK 变化」的应用做轻量 PackageInfo 扫描并写回 Room，
     *   每攒够一批（25 个）才刷新一次 apps / compMeta，避免 200 次重组。
     * 图标完全不在本流程里——由卡片异步从 IconCache 取。
     */
    fun refreshAppList() = viewModelScope.launch(Dispatchers.IO) {
        val repo = AppRepository(getApplication())
        val fast = repo.fastList()

        // 阶段一：即时列表（计数先用缓存兜底）
        val cached = repo.cachedAll()
        apps.value = fast.map { f ->
            val c = cached[f.pkg]
            if (c != null && c.apkPath == f.apkPath && c.versionName.isNotEmpty()) {
                AppItem(
                    pkg = f.pkg, label = f.label, version = c.versionName, isSystem = f.isSystem,
                    apkPath = f.apkPath, activityCount = c.activityCount, serviceCount = c.serviceCount,
                    receiverCount = c.receiverCount, providerCount = c.providerCount,
                    installTime = c.installTime, updateTime = c.updateTime
                )
            } else {
                AppItem(
                    pkg = f.pkg, label = f.label, version = "-", isSystem = f.isSystem,
                    apkPath = f.apkPath, activityCount = 0, serviceCount = 0,
                    receiverCount = 0, providerCount = 0, installTime = 0L, updateTime = 0L
                )
            }
        }

        // 使用情况访问授权时，加载最近启动时间（供「最近使用」排序）
        launch { lastLaunch.value = loadLastLaunch() }
        // 清理已卸载应用的缓存行
        launch { repo.prune(fast.map { it.pkg }) }

        // 阶段二：只富化缓存缺失 / APK 变化的项
        val needEnrich = fast.filter { f ->
            val c = cached[f.pkg]
            c == null || c.apkPath != f.apkPath || c.versionName.isEmpty()
        }
        if (needEnrich.isEmpty()) return@launch

        val batch = mutableListOf<AppRepository.Meta>()
        needEnrich.forEachIndexed { idx, f ->
            val meta = runCatching { repo.enrich(f) }.getOrDefault(
                AppRepository.Meta(f.pkg, "-", f.isSystem, f.apkPath, 0, 0, 0, 0, 0, 0)
            )
            batch.add(meta)
            // 每 25 个或末尾批量刷新一次，控制重组次数
            if (idx % 25 == 24 || idx == needEnrich.lastIndex) {
                val map = batch.associateBy { it.pkg }
                compMeta.value = compMeta.value + map
                apps.value = apps.value.map { it ->
                    map[it.pkg]?.let { m -> it.copy(
                        version = m.version, activityCount = m.activityCount,
                        serviceCount = m.serviceCount, receiverCount = m.receiverCount,
                        providerCount = m.providerCount, installTime = m.installTime,
                        updateTime = m.updateTime
                    ) } ?: it
                }
                batch.clear()
            }
        }
    }

    /** 从 UsageStats 取各应用最近启动时间（需「使用情况访问」权限） */
    private fun loadLastLaunch(): Map<String, Long> {
        val app = getApplication<Application>()
        if (!PermissionHelper.hasUsageAccess(app)) return emptyMap()
        val usm = app.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
            ?: return emptyMap()
        val end = System.currentTimeMillis()
        val start = end - 1000L * 60 * 60 * 24 * 30
        return runCatching {
            usm.queryUsageStats(UsageStatsManager.INTERVAL_BEST, start, end)
                .filter { it.lastTimeUsed > 0 }
                .associate { it.packageName to it.lastTimeUsed }
        }.getOrDefault(emptyMap())
    }

    /** 同步取图标内存命中（首帧占位用） */
    fun iconFor(pkg: String) = IconCache.get(pkg)

    /** 异步解码图标（仅可见卡片调用）；命中内存直接返回 */
    suspend fun loadIcon(pkg: String, apkPath: String): Bitmap? =
        IconCache.load(getApplication<Application>().packageManager, pkg, apkPath)

    /**
     * 进程采样协程（生命周期感知、后台自动暂停）：
     * - 前台：每 [PROCESS_INTERVAL] 采样一次（2s，远轻于原 500ms 全量 su 扫描）；
     * - 后台：跳过采集、只做 delay，CPU 占用趋近 0；
     * - 系统 CPU 循环同样受前台门控，且测量后显式 delay，避免忙等占用核心。
     */
    private fun startProcessPolling() {
        viewModelScope.launch(Dispatchers.IO) {
            var iter = 0
            while (isActive) {
                if (appForeground.value) {
                    refreshProcesses()
                    if (!perms.value.root && ++iter % 10 == 0) refreshPermissions()
                }
                delay(PROCESS_INTERVAL)
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                if (appForeground.value) {
                    systemCpuPercent.value = sampler.sampleSystemCpu(SYSTEM_CPU_SLEEP)
                }
                delay(SYSTEM_CPU_INTERVAL)
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
        val classified = BehaviorClassifier.classifyAll(manifest)
        return manifest.components.map { c ->
            val b = classified[c.className]
            ComponentMetric(
                packageName = manifest.packageName,
                className = c.className,
                type = c.type,
                // 优先显示具体 SDK 名（用户看得懂），没命中名单就退回行为标签
                description = b?.sdk?.name ?: b?.primary?.label ?: "",
                // 运行时指标：由采集层聚合填充；静态阶段给保守默认
                coreAffinity = if (c.exported) 0.4f else 0.6f,
                behaviorTags = b?.behaviors?.map { it.label } ?: emptyList(),
                staticRisk = b?.staticRisk ?: 0,
                safeToBlock = b?.safeToBlock ?: false,
                sideEffect = b?.sideEffect ?: ""
            )
        }
    }

    /** 应用级行为概览：「12 个自启、3 个前台服务」这种 */
    fun behaviorSummary(pkg: String): Map<BehaviorClassifier.Behavior, Int> =
        appCache.value[pkg]?.let { BehaviorClassifier.summarize(it) } ?: emptyMap()

    fun riskOf(m: ComponentMetric) = RiskScorer.score(m)
    fun tierOf(m: ComponentMetric) = RiskScorer.tier(riskOf(m).total)

    fun toggleBlock(m: ComponentMetric) {
        blocked.value = if (m.className in blocked.value) blocked.value - m.className
                        else blocked.value + m.className
        rebuildRulesJson()
    }

    // ══════════════════ 真正执行冻结 ══════════════════

    /** 执行状态。UI 靠它显示进度条和结果 */
    sealed interface ApplyState {
        data object Idle : ApplyState
        data class Running(val done: Int, val total: Int) : ApplyState
        data class Done(val result: ComponentController.BatchResult) : ApplyState
        data class Failed(val message: String) : ApplyState
    }

    val applyState = MutableStateFlow<ApplyState>(ApplyState.Idle)

    /** 执行通道说明，设置页和详情页都要显示 */
    fun channelHint(): String = ComponentController.channelHint()

    val dualLayerAvailable: Boolean get() = ComponentController.dualLayerAvailable

    /**
     * 把当前勾选的阻断名单真正落地。
     *
     * 之前这个开关只改内存里的 blocked 集合，导出 JSON 给 Xposed 模块用 ——
     * 但用户点了开关看到状态变了，会以为已经生效了。这是个体验陷阱。
     * 现在直接调 PM + IFW 执行。
     *
     * 传 types 是为了让 IFW 层能工作：IFW 规则按组件类型分组，
     * 而且 Provider 不支持 IFW 得跳过。
     */
    fun applyBlocking(pkg: String) = viewModelScope.launch {
        val manifest = appCache.value[pkg] ?: run {
            applyState.value = ApplyState.Failed("还没扫描这个应用")
            return@launch
        }
        val targets = manifest.components
            .filter { it.className in blocked.value }
            .map { it.className }
        if (targets.isEmpty()) {
            applyState.value = ApplyState.Failed("没有勾选任何组件")
            return@launch
        }

        val types = manifest.components.associate { it.className to it.type }
        applyState.value = ApplyState.Running(0, targets.size)

        val result = ComponentController.setEnabled(
            context = getApplication(),
            packageName = pkg,
            components = targets,
            enabled = false,
            types = types,
            onProgress = { done, total ->
                applyState.value = ApplyState.Running(done, total)
            }
        )
        applyState.value = ApplyState.Done(result)
        refreshBlockedState(pkg)
    }

    /** 恢复这个应用被冻的全部组件 */
    fun restorePackage(pkg: String) = viewModelScope.launch {
        val manifest = appCache.value[pkg] ?: return@launch
        val all = manifest.components.map { it.className }
        applyState.value = ApplyState.Running(0, all.size)

        val result = ComponentController.resetPackage(getApplication(), pkg, all)
        applyState.value = ApplyState.Done(result)
        blocked.value = blocked.value - all.toSet()
        rebuildRulesJson()
    }

    /**
     * 从系统读回真实的冻结状态。
     *
     * 打开详情页时调一次，让 UI 反映真实情况而不是上次的内存状态 ——
     * 用户可能用别的工具（冰箱、MyAndroidTools）动过同一批组件。
     */
    fun refreshBlockedState(pkg: String) = viewModelScope.launch {
        val manifest = appCache.value[pkg] ?: return@launch
        val real = ComponentController.blockedSetOf(
            getApplication(), pkg, manifest.components.map { it.className }
        )
        // 只替换这个包的部分，别动其他包的勾选
        val others = blocked.value - manifest.components.map { it.className }.toSet()
        blocked.value = others + real
        rebuildRulesJson()
    }

    fun clearApplyState() {
        applyState.value = ApplyState.Idle
    }

    /**
     * 一键优化：对所有已扫描（缓存）的应用自动阻断中高风险组件。
     *
     * 改用 pickSafeCandidates —— 只挑 safeToBlock 的。原来的版本会把桌面入口
     * 和前台服务一起冻掉，用户点完发现图标没了，这种「优化」不如不做。
     */
    fun quickOptimize(): Int {
        val allBlocked = mutableSetOf<String>()
        appCache.value.values.forEach { manifest ->
            allBlocked += RiskScorer.pickSafeCandidates(buildMetrics(manifest))
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

    companion object {
        /** 前台进程列表刷新间隔（原 500ms 全量 su 扫描过密，改为 2s） */
        private const val PROCESS_INTERVAL = 2000L
        /** 系统 CPU 测量窗口 */
        private const val SYSTEM_CPU_SLEEP = 500L
        /** 系统 CPU 循环间隔（测量后显式 delay，避免忙等占核心） */
        private const val SYSTEM_CPU_INTERVAL = 1000L
    }
}
