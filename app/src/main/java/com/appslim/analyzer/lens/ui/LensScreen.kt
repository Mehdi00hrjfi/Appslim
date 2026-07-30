package com.appslim.analyzer.lens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appslim.analyzer.lens.LensViewModel
import com.appslim.analyzer.lens.PermissionLevel
import com.appslim.analyzer.lens.model.ProfileResult
import com.appslim.analyzer.lens.model.ProfileState
import com.appslim.analyzer.ui.PermissionHelper
import com.appslim.analyzer.ui.components.MiuiCard
import com.appslim.analyzer.ui.components.MiuiPageBg
import com.appslim.analyzer.ui.components.MiuiSectionTitle
import com.appslim.analyzer.ui.theme.MiuiGreen
import com.appslim.analyzer.ui.theme.MiuiOrange
import com.appslim.analyzer.ui.theme.MiuiRed
import com.appslim.analyzer.ui.theme.MiuiSurface
import com.appslim.analyzer.ui.theme.MiuiTextPrimary
import com.appslim.analyzer.ui.theme.MiuiTextSecondary
import com.appslim.analyzer.ui.theme.MiuiYellow

/** 性能检测页（v0.5.4：状态反馈 + 权限三档展示） */
@Composable
fun LensScreen(vm: LensViewModel, pkg: String, autoStart: Boolean = false, onBack: () -> Unit) {
    val label by vm.appLabel.collectAsState()
    val level by vm.permissionLevel.collectAsState()
    val acts by vm.activities.collectAsState()
    val dex by vm.dexTags.collectAsState()
    val result by vm.profileResult.collectAsState()
    val state by vm.profileState.collectAsState()
    val running by vm.isRunning.collectAsState()
    val tick by vm.progressTick.collectAsState()
    val total by vm.progressTotal.collectAsState()
    val loadError by vm.loadError.collectAsState()

    LaunchedEffect(pkg) { vm.load(pkg) }

    // 「使用情况访问」权限（决定能否读到前台 Activity）+「显示在其他应用上层」（决定检测后能否自动返回）
    val context = LocalContext.current
    var usageGranted by remember { mutableStateOf(PermissionHelper.hasUsageAccess(context)) }
    var overlayGranted by remember { mutableStateOf(PermissionHelper.hasOverlayPermission(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageGranted = PermissionHelper.hasUsageAccess(context)
                overlayGranted = PermissionHelper.hasOverlayPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    // 从「一键检测」直达时（详情页 auto=1），加载完成后自动开始检测，无需再点一次。
    // 仅在已授予悬浮窗权限时自动跑——否则无法自动返回，交给用户先看权限引导再手动启动。
    LaunchedEffect(pkg, autoStart) {
        if (autoStart && overlayGranted && !vm.isRunning.value && vm.profileResult.value == null) {
            vm.runProfile()
        }
    }

    val levelColor = when (level) {
        PermissionLevel.ROOT -> MiuiGreen
        PermissionLevel.ADB -> MiuiOrange
        PermissionLevel.NORMAL -> MiuiTextSecondary
    }

    MiuiPageBg {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = MiuiTextPrimary)
                    }
                    Column(modifier = Modifier.weight(1f).padding(horizontal = 4.dp)) {
                        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = MiuiTextPrimary)
                        Text(pkg, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MiuiTextSecondary)
                    }
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = levelColor.copy(alpha = 0.12f))
                    ) {
                        Text(level.label, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            color = levelColor, modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp))
                    }
                }
            }

            item {
                Button(
                    onClick = { vm.runProfile() },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MiuiGreen)
                ) {
                    Text(
                        if (running) "检测中…" else "启动并检测（10 秒）",
                        fontSize = 15.sp
                    )
                }
                Text(
                    if (overlayGranted)
                        "将启动该应用并在前台运行 10 秒，每 500ms 采样一次（Activity / CPU / 内存），结束后自动返回本应用并展示结果。"
                    else
                        "将启动该应用并在前台运行 10 秒，每 500ms 采样一次。检测结束后点通知即可返回（授予「显示在其他应用上层」可自动返回）。",
                    fontSize = 12.sp, color = MiuiTextSecondary, modifier = Modifier.padding(top = 6.dp)
                )
            }

            // 加载失败提示（不再闪退，直接显示原因，详见崩溃日志）
            if (loadError != null) {
                item {
                    MiuiCard {
                        Text("加载失败", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuiRed)
                        Spacer(Modifier.height(6.dp))
                        Text(loadError ?: "", fontSize = 12.sp, color = MiuiTextSecondary)
                    }
                }
            }

            // 权限引导：没有「使用情况访问」就看不到具体 Activity（普通模式核心缺口）
            if (!usageGranted) {
                item {
                    MiuiCard {
                        Text("需要「使用情况访问」权限", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuiOrange)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "授予后才能在普通模式下读取目标 App 启动了哪些 Activity。不授权也能跑检测，但 Activity 列表会为空。",
                            fontSize = 12.sp, color = MiuiTextSecondary
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { PermissionHelper.openUsageSettings(context) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MiuiOrange)
                        ) {
                            Text("去开启使用情况访问", fontSize = 14.sp)
                        }
                    }
                }
            }

            // 权限引导：没有「显示在其他应用上层」则检测完成无法自动返回（Android 10+ 后台启动限制）
            if (!overlayGranted) {
                item {
                    MiuiCard {
                        Text("需要「显示在其他应用上层」权限", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuiOrange)
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Android 10 之后，应用启动别的 App 后系统会禁止它再自己切回前台。授予此权限后，检测满 10 秒会自动跳回本应用并展示结果。\n不授予也能用：检测结束后点通知即可返回。",
                            fontSize = 12.sp, color = MiuiTextSecondary
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { PermissionHelper.openOverlaySettings(context) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MiuiOrange)
                        ) {
                            Text("去开启（仅用于自动返回）", fontSize = 14.sp)
                        }
                    }
                }
            }

            // 能力分级说明
            item {
                MiuiCard {
                    MiuiSectionTitle("当前能力（${level.label}）")
                    val caps = when (level) {
                        PermissionLevel.NORMAL -> listOf("✔ Activity 列表", "✔ 启动耗时", "✔ Activity 数量", "✘ CPU / 内存 / Activity 栈（需 ADB 或 Root）")
                        PermissionLevel.ADB -> listOf("✔ Activity 列表", "✔ 启动耗时", "✔ CPU / 内存", "✔ Activity 栈（dumpsys）")
                        PermissionLevel.ROOT -> listOf("✔ Activity 列表", "✔ 启动耗时", "✔ CPU / 内存（精确）", "✔ Activity 栈 / 子进程", "✔ Hook 准备")
                    }
                    caps.forEach { Text(it, fontSize = 13.sp, color = MiuiTextPrimary, modifier = Modifier.padding(vertical = 2.dp)) }
                }
            }

            // 状态条（检测中）
            if (running) {
                item {
                    MiuiCard {
                        val tip = when (state) {
                            is ProfileState.Launching -> "正在启动应用…"
                            is ProfileState.Sampling -> "正在采样 $tick/$total"
                            is ProfileState.Finishing -> "正在汇总结果…"
                            else -> "检测中…"
                        }
                        Text(tip, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = MiuiGreen)
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { if (total > 0) tick / total.toFloat() else 0.05f },
                            modifier = Modifier.fillMaxWidth().height(6.dp),
                            color = MiuiGreen, trackColor = MiuiGreen.copy(alpha = 0.15f), drawStopIndicator = {}
                        )
                    }
                }
            }

            // 结果区
            if (result != null) {
                val r = result!!
                if (r.errorMessage.isNotBlank()) {
                    item {
                        MiuiCard {
                            Text("检测未完成", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MiuiRed)
                            Spacer(Modifier.height(6.dp))
                            Text(r.errorMessage, fontSize = 13.sp, color = MiuiTextSecondary)
                        }
                    }
                } else {
                    item {
                        MiuiCard {
                            MiuiSectionTitle("启动结果")
                            Text("启动耗时：${"%.1f".format(r.launchMs / 1000f)} 秒", fontSize = 14.sp, color = MiuiTextPrimary)
                            Text("采样到 Activity：${r.activities.size} 个", fontSize = 14.sp, color = MiuiTextPrimary)
                            Text("采样到进程：${r.processes.size} 个", fontSize = 14.sp, color = MiuiTextPrimary)
                            Spacer(Modifier.height(8.dp))
                            if (!usageGranted) {
                                Text("未授予「使用情况访问」，Activity 列表为空。去上方开启后可看到具体 Activity。",
                                    fontSize = 12.sp, color = MiuiOrange)
                            } else if (r.activities.isEmpty()) {
                                Text("本次未捕获到前台 Activity（可能目标启动较慢，可重试）。",
                                    fontSize = 12.sp, color = MiuiTextSecondary)
                            }
                            if (level == PermissionLevel.NORMAL) {
                                Text("普通模式内存读不到（需 ADB/Root）；CPU 由 /proc 计算，Activity 由使用情况访问读取。",
                                    fontSize = 12.sp, color = MiuiTextSecondary)
                            }
                        }
                    }
                    item {
                        MiuiCard {
                            MiuiSectionTitle("启动的 Activity（按占用排序）")
                            if (r.activities.isEmpty()) {
                                Text("无数据（见上方说明）", fontSize = 13.sp, color = MiuiTextSecondary)
                            } else {
                                r.activities.sortedByDescending { it.peakCpu }.forEachIndexed { i, a ->
                                    ActivityUsageRow(a, isTop = i == 0)
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                        Spacer(Modifier.height(12.dp))
                        MiuiCard {
                            MiuiSectionTitle("进程资源占用（采样 ${r.processes.size} 个，按占用排序）")
                            if (r.processes.isEmpty()) {
                                Text("检测期间未发现该应用进程（普通模式可能需授予悬浮窗/Root 才读得到）", fontSize = 13.sp, color = MiuiTextSecondary)
                            } else {
                                r.processes.sortedByDescending { it.peakCpu }.forEachIndexed { i, p ->
                                    ProcessRow(p, isTop = i == 0)
                                    Spacer(Modifier.height(8.dp))
                                }
                            }
                        }
                    }

                    // 用检测结果去管理：返回应用详情页，可一键优化 / 阻断高占用组件
                    item {
                        Button(
                            onClick = onBack,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MiuiGreen)
                        ) {
                            Text("返回详情并管理", fontSize = 15.sp)
                        }
                    }
                }
            } else if (!running) {
                item {
                    MiuiCard {
                        Text("点击上方按钮开始检测", fontSize = 13.sp, color = MiuiTextSecondary)
                    }
                }
            }

            item {
                MiuiCard {
                    MiuiSectionTitle("Dex 行为标签")
                    if (dex.isEmpty()) {
                        Text("未发现明显系统能力调用", fontSize = 13.sp, color = MiuiTextSecondary)
                    } else {
                        Text(dex.joinToString("、"), fontSize = 13.sp, color = MiuiTextPrimary)
                    }
                    Spacer(Modifier.height(8.dp))
                    MiuiSectionTitle("Activity 列表（${acts.size}）")
                    acts.forEach { a ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                (if (a.launcher) "★ " else "○ ") + a.className.substringAfterLast('.'),
                                fontSize = 13.sp, color = MiuiTextPrimary, modifier = Modifier.weight(1f)
                            )
                            if (!a.exported) {
                                Text("未 exported", fontSize = 11.sp, color = MiuiOrange)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityUsageRow(a: ProfileResult.ActivityUsage, isTop: Boolean = false) {
    val tint = when {
        a.avgCpu >= 60f -> MiuiRed
        a.avgCpu >= 25f -> MiuiOrange
        a.avgCpu >= 5f -> MiuiYellow
        else -> MiuiGreen
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.className.substringAfterLast('.'), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = MiuiTextPrimary, modifier = Modifier.weight(1f))
            if (isTop) Text("🔥 占用最高", fontSize = 11.sp, color = MiuiRed)
            Text("均 %.0f%% / 峰 %.0f%%".format(a.avgCpu, a.peakCpu), fontSize = 12.sp, color = tint)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (a.peakCpu / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = tint, trackColor = tint.copy(alpha = 0.15f), drawStopIndicator = {}
        )
    }
}

@Composable
private fun ProcessRow(p: ProfileResult.ProcessResource, isTop: Boolean = false) {
    val tint = when {
        p.avgCpu >= 60f -> MiuiRed
        p.avgCpu >= 25f -> MiuiOrange
        p.avgCpu >= 5f -> MiuiYellow
        else -> MiuiGreen
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.processName.substringAfterLast('.'), fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = MiuiTextPrimary, modifier = Modifier.weight(1f))
            if (isTop) Text("🔥 占用最高", fontSize = 11.sp, color = MiuiRed)
            Text("PID ${p.pid}", fontSize = 11.sp, color = MiuiTextSecondary)
        }
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("均 %.0f%%".format(p.avgCpu), fontSize = 12.sp, color = tint)
            Text("峰 %.0f%%".format(p.peakCpu), fontSize = 12.sp, color = MiuiTextSecondary)
            Text("内存 %dMB".format(p.memMb), fontSize = 12.sp, color = MiuiTextSecondary)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { (p.peakCpu / 100f).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            color = tint, trackColor = tint.copy(alpha = 0.15f), drawStopIndicator = {}
        )
    }
}
