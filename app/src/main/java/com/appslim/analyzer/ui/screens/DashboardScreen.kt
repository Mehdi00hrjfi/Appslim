package com.appslim.analyzer.ui.screens

import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appslim.analyzer.ui.MainViewModel
import com.appslim.analyzer.ui.MainViewModel.ProcessItem
import com.appslim.analyzer.ui.components.MiuiArrowRow
import com.appslim.analyzer.ui.components.MiuiCard
import com.appslim.analyzer.ui.components.MiuiDividerLine
import com.appslim.analyzer.ui.components.MiuiInfoRow
import com.appslim.analyzer.ui.components.MiuiPageBg
import com.appslim.analyzer.ui.components.MiuiSectionTitle
import com.appslim.analyzer.ui.components.MiuiStatusCard
import com.appslim.analyzer.ui.components.AppIcon
import com.appslim.analyzer.ui.theme.MiuiGreen
import com.appslim.analyzer.ui.theme.MiuiOrange
import com.appslim.analyzer.ui.theme.MiuiRed
import com.appslim.analyzer.ui.theme.MiuiTextPrimary
import com.appslim.analyzer.ui.theme.MiuiTextSecondary

/** 总览：MIUI / LSPosed 风格状态页（使用 LazyColumn 保证滚动流畅） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val perms by vm.perms.collectAsState()
    val usageGranted by vm.usageGranted.collectAsState()
    val processes by vm.processes.collectAsState()
    val selected by vm.selectedProcess.collectAsState()
    val detail by vm.processDetail.collectAsState()
    val systemCpu by vm.systemCpuPercent.collectAsState()

    var showDetail by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val allReady = perms.root || perms.shizuku || usageGranted
    val statusTitle = when {
        perms.root -> "已获取 Root"
        perms.shizuku -> "Shizuku 已授权"
        usageGranted -> "基础权限已就绪"
        else -> "权限未就绪"
    }
    val statusSub = when {
        perms.root -> "可阻断组件 / 生成 Hook 规则 / 后台分析"
        perms.shizuku -> "大部分分析能力可用，Root 功能受限"
        usageGranted -> "可扫描应用与风险评分"
        else -> "请授予应用列表权限或 Root/Shizuku"
    }

    MiuiPageBg {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "AppSlim",
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuiTextPrimary
                )
                Text(
                    text = "智能应用精简平台",
                    fontSize = 14.sp,
                    color = MiuiTextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp)
                )
            }

            item {
                MiuiStatusCard(
                    title = statusTitle,
                    subtitle = statusSub,
                    isActive = allReady
                )
            }

            // 系统 CPU 总览
            item {
                MiuiCard {
                    MiuiSectionTitle("系统 CPU")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${systemCpu.toInt()}%",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                systemCpu >= 80 -> MiuiRed
                                systemCpu >= 50 -> MiuiOrange
                                else -> MiuiGreen
                            },
                            modifier = Modifier.padding(end = 12.dp)
                        )
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { systemCpu / 100f },
                            modifier = Modifier.weight(1f).height(8.dp),
                            color = when {
                                systemCpu >= 80 -> MiuiRed
                                systemCpu >= 50 -> MiuiOrange
                                else -> MiuiGreen
                            },
                            trackColor = MiuiGreen.copy(alpha = 0.15f),
                            drawStopIndicator = {}
                        )
                    }
                }
            }

            // 软件进程卡片（按图片中进程管理器风格：真实图标 + 名称 + 占用百分比）
            item {
                MiuiCard {
                    MiuiSectionTitle("软件进程")
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        processes.take(6).forEachIndexed { index, p ->
                            ProcessRow(
                                process = p,
                                onClick = {
                                    vm.selectProcess(p)
                                    showDetail = true
                                }
                            )
                            if (index < processes.size - 1 && index < 5) {
                                MiuiDividerLine()
                            }
                        }
                        if (processes.isEmpty()) {
                            Text(
                                text = if (allReady) "正在采集进程数据…（首次约 1 秒）"
                                       else "暂无进程数据，请授予 Root 或应用列表权限",
                                fontSize = 13.sp,
                                color = MiuiTextSecondary
                            )
                        }
                    }
                }
            }

            // 设备信息
            item {
                MiuiCard {
                    MiuiSectionTitle("设备信息")
                    MiuiInfoRow("系统版本", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    MiuiDividerLine()
                    MiuiInfoRow("设备型号", Build.MODEL)
                    MiuiDividerLine()
                    MiuiInfoRow("系统架构", Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown")
                    MiuiDividerLine()
                    MiuiInfoRow("包名", "com.appslim.analyzer")
                    MiuiDividerLine()
                    MiuiInfoRow("版本", "0.5.4")
                }
            }

            // 快捷入口
            item {
                MiuiCard {
                    MiuiSectionTitle("快捷入口")
                    MiuiArrowRow(
                        title = "扫描已安装应用",
                        subtitle = "重新加载应用列表与组件数据",
                        onClick = {
                            vm.scanInstalled()
                            Toast.makeText(context, "正在重新加载应用列表…", Toast.LENGTH_SHORT).show()
                        }
                    )
                    MiuiDividerLine()
                    MiuiArrowRow(
                        title = "一键优化",
                        subtitle = "自动阻断所有已扫描应用的高风险组件",
                        onClick = {
                            val n = vm.quickOptimize()
                            Toast.makeText(context, "已自动阻断 $n 个高风险组件", Toast.LENGTH_SHORT).show()
                        }
                    )
                    MiuiDividerLine()
                    MiuiArrowRow(
                        title = "生成 Hook 规则",
                        subtitle = "导出 LSPosed 模块用的 rules.json",
                        onClick = {
                            vm.packModule()?.let {
                                Toast.makeText(context, "已生成模块：${it.name}", Toast.LENGTH_LONG).show()
                            } ?: Toast.makeText(context, "阻断名单为空，请先扫描并优化应用", Toast.LENGTH_SHORT).show()
                        }
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    // 进程详情底部弹窗：展示 Activity/Service 与 Kill 按钮
    if (showDetail && selected != null) {
        ModalBottomSheet(
            onDismissRequest = {
                showDetail = false
                vm.selectedProcess.value = null
            },
            sheetState = sheetState,
            dragHandle = null,
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            ProcessDetailSheet(
                process = selected!!,
                detail = detail,
                hasRoot = perms.root,
                onKill = {
                    vm.killProcess(selected!!)
                    showDetail = false
                }
            )
        }
    }
}

@Composable
private fun ProcessRow(process: ProcessItem, onClick: () -> Unit) {
    val tint = when {
        process.usagePercent >= 100 -> MiuiRed
        process.usagePercent >= 50 -> MiuiOrange
        else -> MiuiGreen
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(
                bitmap = process.icon,
                size = 40.dp,
                placeholderSize = 22.dp
            )
            Spacer(Modifier.size(12.dp))
            Text(
                text = process.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MiuiTextPrimary
            )
        }
        Text(
            text = "%.1f%%".format(process.usagePercent),
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            color = tint
        )
    }
}

@Composable
private fun ProcessDetailSheet(
    process: ProcessItem,
    detail: Pair<String, String>?,
    hasRoot: Boolean,
    onKill: () -> Unit
) {
    val scroll = rememberScrollState()
    val sheetHeight = (LocalConfiguration.current.screenHeightDp * 0.75).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(sheetHeight)
            .padding(horizontal = 20.dp)
            .padding(top = 24.dp)
    ) {
        // 上半部分可滚动
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(bottom = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppIcon(
                    bitmap = process.icon,
                    size = 52.dp,
                    placeholderSize = 28.dp
                )
                Spacer(Modifier.size(14.dp))
                Column {
                    Text(
                        text = process.label,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuiTextPrimary
                    )
                    Text(
                        text = process.pkg,
                        fontSize = 12.sp,
                        color = MiuiTextSecondary
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("CPU", fontSize = 12.sp, color = MiuiTextSecondary)
                    Text(
                        "%.1f%%".format(process.usagePercent),
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (process.usagePercent >= 100) MiuiRed else MiuiGreen
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("内存", fontSize = 12.sp, color = MiuiTextSecondary)
                    Text(
                        "${process.memoryMb} MB",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuiTextPrimary
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("PID", fontSize = 12.sp, color = MiuiTextSecondary)
                    Text(
                        "${process.pid}",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MiuiTextPrimary
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = "Activities",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MiuiTextPrimary
            )
            Text(
                text = detail?.first?.takeIf { it.isNotBlank() } ?: "暂无 Activity 信息",
                fontSize = 12.sp,
                color = MiuiTextSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )

            Spacer(Modifier.height(16.dp))
            Text(
                text = "Services",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MiuiTextPrimary
            )
            Text(
                text = detail?.second?.takeIf { it.isNotBlank() } ?: "暂无 Service 信息",
                fontSize = 12.sp,
                color = MiuiTextSecondary,
                modifier = Modifier.padding(top = 6.dp)
            )
        }

        // 底部 Kill 按钮固定
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp)
        ) {
            Button(
                onClick = onKill,
                enabled = true,
                modifier = Modifier.fillMaxWidth(),
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = MiuiRed)
            ) {
                Text("结束进程", fontSize = 15.sp)
            }
            if (!hasRoot) {
                Text(
                    text = "未获取 Root，仅可结束普通后台进程",
                    fontSize = 12.sp,
                    color = MiuiTextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
