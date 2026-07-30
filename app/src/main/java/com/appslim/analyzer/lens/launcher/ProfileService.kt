package com.appslim.analyzer.lens.launcher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.appslim.analyzer.lens.model.ProfileResult
import com.appslim.analyzer.lens.model.ProfileState
import com.appslim.analyzer.lens.model.ProfileStatus
import com.appslim.analyzer.util.CrashLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 前台检测服务 —— 严格移植自 APPLENS（Horizen5/APPLENS: service/SamplingService.kt）。
 *
 * 与 APPLENS 完全一致的稳定性要点：
 *  1. 前台服务 + 常驻通知 + PARTIAL_WAKE_LOCK，启动目标 App 后本应用退后台也不被杀；
 *  2. 通过 packageManager.getLaunchIntentForPackage 启动目标，等 1500ms；
 *  3. 用 ApplensSampler 采样（UsageStats 读前台 Activity + top/dumpsys 兜底），每 500ms 一次、持续 10s；
 *  4. 全程 try/catch，异常降级为失败结果而非抛飞；
 *  5. 不扫描 /proc、不在后台强拉回前台（避免 HyperOS 上的后台启动限制导致闪退）。
 *
 * 结果通过 ProfileSession 单例回传给 Compose UI。
 */
class ProfileService : Service() {

    companion object {
        const val CHANNEL_ID = "appslim_profile"
        const val NOTIF_ID = 2001
        const val EXTRA_PKG = "pkg"
        const val EXTRA_LABEL = "label"
        const val EXTRA_LAUNCHER = "launcher"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + CrashLogger.coroutineHandler())
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val started = runCatching { startForeground(NOTIF_ID, buildNotification("准备检测…", null)) }
            .onFailure { CrashLogger.logToFile("ProfileService.onCreate startForeground", it) }
            .isSuccess
        // 若无法进入前台服务状态，直接退出，避免系统因「未调用 startForeground」强杀本进程
        if (!started) { stopSelf(); return }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "性能检测", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "启动行为检测前台服务" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun buildNotification(text: String, contentIntent: PendingIntent?): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("性能检测中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .apply { if (contentIntent != null) setContentIntent(contentIntent) }
            .build()

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val pkg = intent?.getStringExtra(EXTRA_PKG)
        if (pkg.isNullOrBlank()) { stopSelf(); return START_NOT_STICKY }

        val label = intent.getStringExtra(EXTRA_LABEL) ?: pkg
        val launcher = intent.getStringExtra(EXTRA_LAUNCHER) ?: ""

        if (ProfileSession.running.value) { stopSelf(); return START_NOT_STICKY }
        ProfileSession.reset()
        ProfileSession.setRunning(true)
        ProfileSession.setState(ProfileState.Launching)

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AppSlim::Profile")
        runCatching { wakeLock?.acquire(20_000L) }

        scope.launch {
            try {
                val result = runProfile(pkg, label, launcher)
                ProfileSession.setResult(result)
                ProfileSession.setState(ProfileState.Done)
                notifyDone(result, label)
            } catch (e: Throwable) {
                val err = ProfileResult(
                    pkg, launcher, 0, false, emptyList(), emptyList(),
                    "检测异常：" + (e.message ?: e.javaClass.simpleName),
                    ProfileStatus.ERROR
                )
                ProfileSession.setResult(err)
                ProfileSession.setState(ProfileState.Done)
                notifyDone(err, label)
            } finally {
                runCatching { wakeLock?.release() }
                ProfileSession.setRunning(false)
                // 采样结束后自动把本应用带回前台，让用户在 Lens 页直接看到结果
                bringAppToFront()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runProfile(pkg: String, label: String, launcher: String): ProfileResult {
        val t0 = System.currentTimeMillis()
        val sampler = ApplensSampler(this)

        // 1) 启动目标 App（APPLENS 同款：标准启动 Intent + 等 1500ms）
        ProfileSession.setState(ProfileState.Launching)
        val launch = packageManager.getLaunchIntentForPackage(pkg)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { startActivity(launch) }
            delay(1500)
        }

        // 2) 采样 10s @ 500ms（UsageStats 读前台 Activity + top/dumpsys 兜底）
        ProfileSession.setState(ProfileState.Sampling(1))
        val report = sampler.sample(
            packageName = pkg,
            appLabel = label,
            config = ApplensSampler.Config(durationMs = 10_000, intervalMs = 500)
        ) { cur, total, _ ->
            ProfileSession.setProgress(cur * 100 / total, cur, total)
            ProfileSession.setState(ProfileState.Sampling(cur))
        }

        ProfileSession.setState(ProfileState.Finishing)

        // 3) 汇总为 ProfileResult
        val launchMs = (report.samples.firstOrNull()?.timestamp ?: t0).minus(t0).coerceAtLeast(0)
        val launchedOk = report.samples.isNotEmpty()
        val activities = report.stats.map {
            ProfileResult.ActivityUsage(
                className = it.activityFullName,
                avgCpu = it.avgCpu,
                peakCpu = it.maxCpu
            )
        }
        // 进程资源占用（CPU 来自 top，内存来自 dumpsys meminfo，普通模式内存可能为 0）
        val processes = sampler.sampleProcesses(pkg).map {
            ProfileResult.ProcessResource(
                pid = it.pid,
                processName = it.name,
                avgCpu = it.cpu,
                peakCpu = it.cpu,
                memMb = (it.memKb / 1024).toInt()
            )
        }
        return ProfileResult(
            packageName = pkg,
            launchedActivity = launcher,
            launchMs = launchMs,
            launchedOk = launchedOk,
            activities = activities,
            processes = processes,
            errorMessage = if (launchedOk) "" else "未采到该应用前台 Activity（可能未启动成功，或需授予「使用情况访问」）",
            status = if (launchedOk) ProfileStatus.SUCCESS else ProfileStatus.LAUNCH_FAILED
        )
    }

    /** 采样结束后把本应用（AppSlim Analyzer）带回前台，方便直接查看结果 */
    private fun bringAppToFront() {
        runCatching {
            val self = applicationContext.packageName
            val it = packageManager.getLaunchIntentForPackage(self)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            if (it != null) startActivity(it)
        }.onFailure { CrashLogger.logToFile("ProfileService.bringAppToFront", it) }
    }

    private fun notifyDone(result: ProfileResult, label: String) {
        val self = applicationContext.packageName
        val pi = runCatching {
            val it = packageManager.getLaunchIntentForPackage(self)?.apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            PendingIntent.getActivity(
                this, 0, it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }.getOrNull()

        val ok = result.status == ProfileStatus.SUCCESS
        val text = if (ok) "完成：${result.activities.size} 个 Activity · 耗时 ${"%.1f".format(result.launchMs / 1000f)}s"
        else "未完成：${result.errorMessage.take(40)}"

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("性能检测 · $label")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(false)
            .setAutoCancel(true)
            .apply { if (pi != null) setContentIntent(pi) }
            .build())
    }

    override fun onDestroy() {
        super.onDestroy()
        runCatching { wakeLock?.release() }
        scope.cancel()
        ProfileSession.setRunning(false)
    }
}
