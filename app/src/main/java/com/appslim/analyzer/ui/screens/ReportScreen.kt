package com.appslim.analyzer.ui.screens

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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appslim.analyzer.ui.MainViewModel
import com.appslim.analyzer.ui.components.AppIcon
import com.appslim.analyzer.ui.components.MiuiCard
import com.appslim.analyzer.ui.components.MiuiDividerLine
import com.appslim.analyzer.ui.components.MiuiPageBg
import com.appslim.analyzer.ui.components.MiuiSectionTitle
import com.appslim.analyzer.ui.theme.MiuiGreen
import com.appslim.analyzer.ui.theme.MiuiOrange
import com.appslim.analyzer.ui.theme.MiuiRed
import com.appslim.analyzer.ui.theme.MiuiSurface
import com.appslim.analyzer.ui.theme.MiuiTextPrimary
import com.appslim.analyzer.ui.theme.MiuiTextSecondary
import com.appslim.analyzer.ui.theme.MiuiYellow

/** 性能报告：展示全局优化成果、Top 资源占用进程、阻断统计 */
@Composable
fun ReportScreen(vm: MainViewModel) {
    val blocked by vm.blocked.collectAsState()
    val processes by vm.processes.collectAsState()
    val systemCpu by vm.systemCpuPercent.collectAsState()
    val blockedByPkg = vm.blockedByPackage()
    val totalBlocked = blocked.size
    val optimizedApps = blockedByPkg.size

    // 按 CPU / 内存取 Top 5
    val topCpu = processes.sortedByDescending { it.usagePercent }.take(5)
    val topMem = processes.sortedByDescending { it.memoryMb }.take(5)

    MiuiPageBg {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "性能报告",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuiTextPrimary
                )
                Text(
                    text = "全局资源占用与优化成果",
                    fontSize = 13.sp,
                    color = MiuiTextSecondary
                )
            }

            item {
                MiuiCard {
                    MiuiSectionTitle("系统实时状态")
                    StatRow("系统 CPU", "${systemCpu.toInt()}%", systemCpu / 100f,
                        color = when {
                            systemCpu >= 80 -> MiuiRed
                            systemCpu >= 50 -> MiuiOrange
                            else -> MiuiGreen
                        })
                    Spacer(Modifier.height(12.dp))
                    StatRow("运行中进程", "${processes.size} 个", null, MiuiTextPrimary)
                    Spacer(Modifier.height(12.dp))
                    StatRow("已优化应用", "$optimizedApps 个", if (optimizedApps > 0) 1f else 0f, MiuiGreen)
                    Spacer(Modifier.height(12.dp))
                    StatRow("已阻断组件", "$totalBlocked 个", if (totalBlocked > 0) 1f else 0f, MiuiOrange)
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MiuiGreen.copy(alpha = 0.10f)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Text(
                            "自动结论",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MiuiGreen
                        )
                        Text(
                            text = when {
                                totalBlocked == 0 && processes.isEmpty() ->
                                    "暂无数据。请授予 Root 或应用列表权限，并在「应用」页扫描组件后使用「一键优化」。"
                                totalBlocked == 0 ->
                                    "已监控 ${processes.size} 个进程，系统 CPU ${systemCpu.toInt()}%。尚未阻断任何组件，点击「一键优化」可自动处理高风险组件。"
                                else ->
                                    "已阻断 $totalBlocked 个组件（涉及 $optimizedApps 个应用），预计减少后台唤醒与无效 CPU 占用。系统 CPU 当前 ${systemCpu.toInt()}%。"
                            },
                            fontSize = 14.sp,
                            color = MiuiTextPrimary,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            item {
                MiuiCard {
                    MiuiSectionTitle("CPU 占用 Top 5")
                    if (topCpu.isEmpty()) {
                        Text("暂无进程数据", fontSize = 13.sp, color = MiuiTextSecondary)
                    } else {
                        topCpu.forEachIndexed { i, p ->
                            ProcessMiniRow(p.label, p.icon, p.usagePercent, "%")
                            if (i < topCpu.lastIndex) MiuiDividerLine()
                        }
                    }
                }
            }

            item {
                MiuiCard {
                    MiuiSectionTitle("内存占用 Top 5")
                    if (topMem.isEmpty()) {
                        Text("暂无进程数据", fontSize = 13.sp, color = MiuiTextSecondary)
                    } else {
                        topMem.forEachIndexed { i, p ->
                            ProcessMiniRow(p.label, p.icon, p.memoryMb.toFloat(), "MB")
                            if (i < topMem.lastIndex) MiuiDividerLine()
                        }
                    }
                }
            }

            item {
                MiuiCard {
                    MiuiSectionTitle("已优化应用")
                    if (blockedByPkg.isEmpty()) {
                        Text("暂无优化记录", fontSize = 13.sp, color = MiuiTextSecondary)
                    } else {
                        blockedByPkg.entries.take(10).forEachIndexed { i, (pkg, list) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    pkg,
                                    fontSize = 14.sp,
                                    color = MiuiTextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    "${list.size} 个",
                                    fontSize = 13.sp,
                                    color = MiuiGreen,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            if (i < blockedByPkg.size - 1 && i < 9) MiuiDividerLine()
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String, progress: Float?, color: Color) {
    Column {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, modifier = Modifier.weight(1f), fontSize = 14.sp, color = MiuiTextPrimary)
            Text(value, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        }
        if (progress != null) {
            LinearProgressIndicator(
                progress = { progress.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp),
                color = color,
                trackColor = Color(0xFFE8F5E9),
                drawStopIndicator = {}
            )
        }
    }
}

@Composable
private fun ProcessMiniRow(label: String, icon: android.graphics.Bitmap?, value: Float, unit: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AppIcon(bitmap = icon, size = 32.dp, placeholderSize = 18.dp)
        Spacer(Modifier.padding(horizontal = 10.dp))
        Text(label, fontSize = 14.sp, color = MiuiTextPrimary, modifier = Modifier.weight(1f))
        Text(
            "${"%.1f".format(value)}$unit",
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = when {
                unit == "%" && value >= 50 -> MiuiRed
                unit == "%" && value >= 25 -> MiuiOrange
                unit == "%" -> MiuiGreen
                value >= 500 -> MiuiOrange
                else -> MiuiTextPrimary
            }
        )
    }
}
