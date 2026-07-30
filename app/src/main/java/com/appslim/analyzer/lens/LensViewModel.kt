package com.appslim.analyzer.lens

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.appslim.analyzer.lens.launcher.ProfileService
import com.appslim.analyzer.lens.launcher.ProfileSession
import com.appslim.analyzer.lens.model.ActivityResult
import com.appslim.analyzer.lens.model.PerformanceReport
import com.appslim.analyzer.lens.model.ProfileResult
import com.appslim.analyzer.lens.model.ProfileState
import com.appslim.analyzer.lens.scanner.ActivityEnumerator
import com.appslim.analyzer.lens.scanner.DexAnalyzer
import com.appslim.analyzer.lens.scanner.PackageScanner
import com.appslim.analyzer.lens.util.Shell
import com.appslim.analyzer.root.RootShell
import com.appslim.analyzer.util.CrashLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * AppLens 性能检测：编排枚举 → Dex 扫描 → 启动目标 App 并采样。
 *
 * v0.5.5：检测工作移交前台 [ProfileService]（WakeLock 保活 + UsageStats 读前台 Activity），
 * 本 ViewModel 只负责触发服务并桥接 [ProfileSession] 的进度/结果给 UI，不再在 viewModelScope 内
 * 直接跑 10s 采样循环——这是小米 HyperOS 上闪退的根因修复。
 */
class LensViewModel(app: Application) : AndroidViewModel(app) {

    private val pm = app.packageManager
    private val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
    private val packageScanner = PackageScanner(pm)
    private val enumerator = ActivityEnumerator(pm)
    private val dexAnalyzer = DexAnalyzer()

    data class ActivityInfoLite(
        val className: String, val exported: Boolean, val launcher: Boolean
    )

    val packageName = MutableStateFlow<String?>(null)
    val appLabel = MutableStateFlow("")
    val activities = MutableStateFlow<List<ActivityInfoLite>>(emptyList())
    val dexTags = MutableStateFlow<List<String>>(emptyList())
    val results = MutableStateFlow<List<ActivityResult>>(emptyList())
    val permissionLevel = MutableStateFlow(PermissionLevel.NORMAL)
    val progress = MutableStateFlow(Progress(0, 0))
    val loadError = MutableStateFlow<String?>(null)

    /** 以下三个状态直接桥接前台服务的会话单例（见 ProfileSession） */
    val profileResult: StateFlow<ProfileResult?> = ProfileSession.result
    val profileState: StateFlow<ProfileState> = ProfileSession.state
    val isRunning: StateFlow<Boolean> = ProfileSession.running
    val progressTick: StateFlow<Int> = ProfileSession.tick
    val progressTotal: StateFlow<Int> = ProfileSession.totalTicks

    data class Progress(val done: Int, val total: Int)

    /** 加载目标应用：枚举 Activity + Dex 扫描 + 权限检测（全部包 try/catch，避免页面打开即闪退） */
    fun load(pkg: String) {
        loadError.value = null
        try {
            packageName.value = pkg
            val info = packageScanner.scan(pkg)
            appLabel.value = info?.label ?: pkg
            activities.value = enumerator.enumerate(pkg).map {
                ActivityInfoLite(it.className, it.exported, it.launcher)
            }
            viewModelScope.launch(Dispatchers.IO + CrashLogger.coroutineHandler()) {
                dexTags.value = dexAnalyzer.analyze(info?.sourceDir ?: "")
            }
            permissionLevel.value = detectLevel()
            results.value = emptyList()
        } catch (e: Throwable) {
            CrashLogger.logToFile("LensViewModel.load($pkg)", e)
            loadError.value = "加载应用信息失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 三级权限检测 */
    private fun detectLevel(): PermissionLevel {
        if (RootShell.isRootAvailable) return PermissionLevel.ROOT
        val out = Shell.exec("dumpsys cpuinfo")
        if (out.isNotBlank() && !out.contains("Permission Denial", ignoreCase = true)) {
            return PermissionLevel.ADB
        }
        return PermissionLevel.NORMAL
    }

    /** 启动前台检测服务（v0.5.5：不再本地跑循环，交给 ProfileService） */
    fun runProfile() {
        val pkg = packageName.value ?: return
        val app = getApplication<Application>()
        ProfileSession.reset()
        val launcher = activities.value.firstOrNull { it.launcher }?.className
            ?: activities.value.firstOrNull { it.exported }?.className ?: ""
        val intent = Intent(app, ProfileService::class.java).apply {
            putExtra(ProfileService.EXTRA_PKG, pkg)
            putExtra(ProfileService.EXTRA_LABEL, appLabel.value)
            putExtra(ProfileService.EXTRA_LAUNCHER, launcher)
        }
        runCatching { ContextCompat.startForegroundService(app, intent) }
    }

    /** 生成完整报告（供后续导出） */
    fun buildReport(): PerformanceReport? {
        val pkg = packageName.value ?: return null
        return PerformanceReport(
            packageName = pkg,
            label = appLabel.value,
            permissionLevel = permissionLevel.value,
            activities = results.value,
            dexTags = dexTags.value
        )
    }
}
