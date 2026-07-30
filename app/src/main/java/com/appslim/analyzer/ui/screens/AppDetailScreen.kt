package com.appslim.analyzer.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appslim.analyzer.data.model.ComponentType
import com.appslim.analyzer.data.model.RiskTier
import com.appslim.analyzer.ui.MainViewModel
import com.appslim.analyzer.ui.components.MiuiBadge
import com.appslim.analyzer.ui.components.MiuiCard
import com.appslim.analyzer.ui.components.MiuiDividerLine
import com.appslim.analyzer.ui.components.MiuiPageBg
import com.appslim.analyzer.ui.components.MiuiSectionTitle
import com.appslim.analyzer.ui.theme.MiuiSurface
import com.appslim.analyzer.ui.theme.MiuiGreen
import com.appslim.analyzer.ui.theme.MiuiOrange
import com.appslim.analyzer.ui.theme.MiuiRed
import com.appslim.analyzer.ui.theme.MiuiTextPrimary
import com.appslim.analyzer.ui.theme.MiuiTextSecondary
import com.appslim.analyzer.ui.theme.MiuiYellow

/** 应用详情：MIUI 卡片化组件列表 + 风险评分 + 阻断开关 */
@Composable
fun AppDetailScreen(vm: MainViewModel, onBack: () -> Unit, onLens: (String) -> Unit = {}) {
    val pkg by vm.selectedPkg.collectAsState()
    val metrics by vm.metrics.collectAsState()
    val blocked by vm.blocked.collectAsState()
    val scanning by vm.isScanning.collectAsState()
    var filter by remember { mutableStateOf(ComponentType.SERVICE) }

    val shown = metrics.filter { it.type == filter }.sortedByDescending { vm.riskOf(it).total }
    val totalBlocked = metrics.count { it.className in blocked }

    MiuiPageBg {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = MiuiTextPrimary
                    )
                }
                Text(
                    text = pkg.orEmpty(),
                    fontSize = 16.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuiTextPrimary,
                    modifier = Modifier.weight(1f).padding(horizontal = 4.dp)
                )
            }

            Spacer(Modifier.height(8.dp))

            // 状态摘要
            MiuiCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        MiuiSectionTitle("风险评分")
                        Text(
                            text = when {
                                scanning -> "扫描中…"
                                metrics.isEmpty() -> "暂无数据"
                                else -> "${metrics.map { vm.riskOf(it).total }.average().toInt()} / 100"
                            },
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (metrics.isEmpty()) MiuiTextSecondary
                                     else tierColorForScore(metrics.map { vm.riskOf(it).total }.average().toInt())
                        )
                    }
                    MiuiBadge(text = "已阻断 $totalBlocked", color = MiuiGreen)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { vm.quickOptimize() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MiuiGreen)
                ) {
                    Text("一键优化", fontSize = 15.sp)
                }
            }

            Spacer(Modifier.height(12.dp))

            // 性能检测入口
            Button(
                onClick = { pkg?.let { onLens(it) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MiuiGreen)
            ) {
                Text("一键检测", fontSize = 15.sp)
            }

            Spacer(Modifier.height(12.dp))

            // 组件类型筛选
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MiuiSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    ComponentType.entries.forEach { t ->
                        FilterChip(
                            selected = filter == t,
                            onClick = { filter = t },
                            label = { Text("${typeLabel(t)} (${metrics.count { it.type == t }})") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MiuiGreen.copy(alpha = 0.15f),
                                selectedLabelColor = MiuiGreen,
                                containerColor = Color(0xFFF5F5F5),
                                labelColor = MiuiTextPrimary
                            ),
                            border = null
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(shown) { m ->
                    val score = vm.riskOf(m)
                    val tier = RiskScorerTierOf(score.total)
                    val isBlocked = m.className in blocked
                    val tierColor = tierColor(tier)

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = MiuiSurface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    m.className.substringAfterLast('.'),
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    fontFamily = FontFamily.Monospace,
                                    color = MiuiTextPrimary
                                )
                                if (m.description.isNotBlank()) {
                                    Text(
                                        m.description,
                                        fontSize = 12.sp,
                                        color = MiuiTextSecondary,
                                        modifier = Modifier.padding(top = 3.dp)
                                    )
                                }
                                MiuiBadge(
                                    text = "${score.total} · ${tier.label}",
                                    color = tierColor,
                                    modifier = Modifier.padding(top = 8.dp)
                                )
                            }
                            Switch(
                                checked = isBlocked,
                                onCheckedChange = { vm.toggleBlock(m) },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = Color.White,
                                    checkedTrackColor = if (tier == RiskTier.BLOCK) MiuiRed else MiuiGreen,
                                    uncheckedThumbColor = Color.White,
                                    uncheckedTrackColor = Color(0xFFE0E0E0)
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun typeLabel(type: ComponentType): String = when (type) {
    ComponentType.SERVICE -> "服务"
    ComponentType.RECEIVER -> "接收器"
    ComponentType.PROVIDER -> "Provider"
    ComponentType.ACTIVITY -> "Activity"
}

private fun tierColorForScore(score: Int): Color = when {
    score >= 85 -> MiuiRed
    score >= 65 -> MiuiOrange
    score >= 40 -> MiuiYellow
    else -> MiuiGreen
}

private fun tierColor(tier: RiskTier): Color = when (tier) {
    RiskTier.BLOCK -> MiuiRed
    RiskTier.HIGH -> MiuiOrange
    RiskTier.MID -> MiuiYellow
    RiskTier.LOW -> MiuiGreen
}

private fun RiskScorerTierOf(score: Int): RiskTier = when {
    score >= 85 -> RiskTier.BLOCK
    score >= 65 -> RiskTier.HIGH
    score >= 40 -> RiskTier.MID
    else -> RiskTier.LOW
}
