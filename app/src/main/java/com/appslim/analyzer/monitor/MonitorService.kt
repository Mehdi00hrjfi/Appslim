package com.appslim.analyzer.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import com.appslim.analyzer.data.db.AppDatabase
import com.appslim.analyzer.data.db.ComponentEventEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 数据采集前台服务（报告 §5 数据采集层）
 *
 * 周期采集：运行进程 → 线程级 CPU（/proc）→ 写入 Room，
 * 供分析引擎（评分 / 依赖 / SDK 识别）与学习模式消费。
 */
class MonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var runtime: RuntimeMonitor

    override fun onCreate() {
        super.onCreate()
        runtime = RuntimeMonitor(this)
        startForeground(NOTI_ID, buildNotification())
        collectLoop()
    }

    private fun collectLoop() = scope.launch {
        val dao = AppDatabase.get(this@MonitorService).eventDao()
        while (isActive) {
            val targets = dao.watchedPackages().ifEmpty { return@launch }
            for (pkg in targets) {
                for (pid in runtime.pidsOf(pkg)) {
                    val threads = ProcCpuReader.readThreads(pid)
                    dao.insert(
                        ComponentEventEntity(
                            packageName = pkg,
                            kind = "thread_sample",
                            detail = "pid=$pid threads=${threads.size} " +
                                    threads.sortedByDescending { it.totalJiffies }
                                        .take(3).joinToString { "${it.name}:${it.state}" },
                            ts = System.currentTimeMillis()
                        )
                    )
                }
            }
            delay(SAMPLE_INTERVAL)
        }
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "运行时监控", NotificationManager.IMPORTANCE_MIN)
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("AppSlim 监控中")
            .setContentText("正在采集组件运行时行为…")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    override fun onDestroy() { scope.cancel(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "monitor"
        private const val NOTI_ID = 1
        private const val SAMPLE_INTERVAL = 15_000L
    }
}
