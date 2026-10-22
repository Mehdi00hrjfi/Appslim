package com.appslim.analyzer.ui.screens

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
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
import com.appslim.analyzer.analysis.RiskScorer
import com.appslim.analyzer.data.model.ComponentMetric
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
    val applyState by vm.applyState.collectAsState()
    var filter by remember { mutableStateOf(ComponentType.SERVICE) }

    // 进页面先从系统读回真实冻结状态 —— 用户可能用别的工具动过同一批组件，
    // 只信内存里的勾选会显示错。
    LaunchedEffect(pkg) { pkg?.let { vm.refreshBlockedState(it) } }

    // 按综合分排序：没跑过运行时监控时运行时数据全是 0，
    // 光按 riskOf 排会所有组件挤在一起，静态行为分能把自启、推送这些顶上来。
    val shown = metrics.filter { it.type == filter }
        .sortedByDescending { RiskScorer.combined(it) }
    val totalBlocked = metrics.count { it.className in blocked }
    val riskyCount = metrics.count { it.safeToBlock && RiskScorer.combined(it) >= 60 }

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
                        contentDescription = "رجوع",
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
                        MiuiSectionTitle("تقييم الخطورة")
                        val avg = if (metrics.isEmpty()) 0
                                  else metrics.map { RiskScorer.combined(it) }.average().toInt()
                        Text(
                            text = when {
                                scanning -> "جارٍ الفحص…"
                                metrics.isEmpty() -> "لا توجد بيانات"
                                else -> "$avg / 100"
                            },
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (metrics.isEmpty()) MiuiTextSecondary
                                     else tierColorForScore(avg)
                        )
                        if (riskyCount > 0) {
                            Text(
                                "$riskyCount مكوّنات يمكن تعطيلها بأمان",
                                fontSize = 12.sp,
                                color = MiuiTextSecondary,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                    }
                    MiuiBadge(text = "محظور $totalBlocked", color = MiuiGreen)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.quickOptimize() },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MiuiGreen),
                        enabled = riskyCount > 0
                    ) {
                        Text(
                            if (riskyCount > 0) "تحديد $riskyCount عنصر" else "تحسين بنقرة واحدة",
                            fontSize = 14.sp
                        )
                    }
                    // 勾选只是意向，这个按钮才真正落地到系统
                    Button(
                        onClick = { pkg?.let { vm.applyBlocking(it) } },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MiuiOrange),
                        enabled = totalBlocked > 0 && applyState !is MainViewModel.ApplyState.Running
                    ) {
                        Text("تنفيذ التجميد", fontSize = 14.sp)
                    }
                }

                ApplyStatusBar(
                    state = applyState,
                    dualLayer = vm.dualLayerAvailable,
                    onDismiss = { vm.clearApplyState() },
                    onRestore = { pkg?.let { vm.restorePackage(it) } }
                )
            }

            Spacer(Modifier.height(12.dp))

            // 性能检测入口
            Button(
                onClick = { pkg?.let { onLens(it) } },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MiuiGreen)
            ) {
                Text("فحص بنقرة واحدة", fontSize = 15.sp)
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
                    ComponentRow(
                        m = m,
                        isBlocked = m.className in blocked,
                        onToggle = { vm.toggleBlock(m) }
                    )
                }
            }
        }
    }
}

/**
 * 执行状态条。
 *
 * 三件事要让用户看到：
 *   1. 正在跑（进度）
 *   2. 跑完了什么结果（走的哪条通道、多快、有没有走防火墙层）
 *   3. 后悔了怎么办（恢复按钮）
 *
 * 第 2 点里「有没有走防火墙层」值得单独说 —— 只有 PM 层的话，
 * 应用重启时能把自己 enable 回来，用户得知道这个区别。
 */
@Composable
private fun ApplyStatusBar(
    state: MainViewModel.ApplyState,
    dualLayer: Boolean,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
) {
    when (state) {
        is MainViewModel.ApplyState.Idle -> {
            if (!dualLayer) {
                Text(
                    "بدون Root: قد يعود التطبيق للعمل تلقائياً بعد التجميد",
                    fontSize = 11.sp,
                    color = MiuiTextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        is MainViewModel.ApplyState.Running -> {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    "جارٍ التجميد ${state.done} / ${state.total}",
                    fontSize = 12.sp,
                    color = MiuiTextPrimary
                )
                LinearProgressIndicator(
                    progress = {
                        if (state.total == 0) 0f
                        else state.done.toFloat() / state.total
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    color = MiuiOrange,
                    trackColor = Color(0xFFEEEEEE),
                )
            }
        }

        is MainViewModel.ApplyState.Done -> {
            val r = state.result
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    r.describe(),
                    fontSize = 12.sp,
                    color = if (r.allOk) MiuiGreen else MiuiOrange
                )
                if (!r.dualLayer && r.succeeded > 0) {
                    Text(
                        "يعمل طبقة PM فقط، وقد يعود التطبيق بعد إعادة التشغيل. تثبيت Root يفعّل طبقة جدار الحماية.",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MiuiTextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "حسناً",
                        fontSize = 12.sp,
                        color = MiuiGreen,
                        modifier = Modifier.clickable { onDismiss() }
                    )
                    Text(
                        "استعادة الكل",
                        fontSize = 12.sp,
                        color = MiuiRed,
                        modifier = Modifier.clickable { onRestore() }
                    )
                }
            }
        }

        is MainViewModel.ApplyState.Failed -> {
            Text(
                state.message,
                fontSize = 12.sp,
                color = MiuiRed,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clickable { onDismiss() }
            )
        }
    }
}

/**
 * 单个组件卡片。
 *
 * 相比原来多了三样东西：
 *   1. 行为标签 —— 「开机自启」「推送服务」这种，比一串混淆类名有用得多；
 *   2. 不安全标记 —— 桌面入口/前台服务禁用后用户立刻能感知，得先警告；
 *   3. 点开看副作用 —— 默认收起，想知道「关了会怎样」再展开。
 */
@Composable
private fun ComponentRow(
    m: ComponentMetric,
    isBlocked: Boolean,
    onToggle: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val total = RiskScorer.combined(m)
    val tier = RiskScorerTierOf(total)
    val tierColor = tierColor(tier)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MiuiSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                }
                Switch(
                    checked = isBlocked,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = if (m.safeToBlock) MiuiGreen else MiuiRed,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFE0E0E0)
                    )
                )
            }

            // 风险分 + 行为标签，横向可滚，标签多了不会挤爆
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiuiBadge(text = "$total · ${tier.label}", color = tierColor)
                m.behaviorTags.forEach { tag ->
                    MiuiBadge(text = tag, color = behaviorTagColor(tag))
                }
            }

            if (!m.safeToBlock && m.behaviorTags.isNotEmpty()) {
                Text(
                    "تعطيله يؤثر على وظائف ظاهرة، يُنصح بإبقائه",
                    fontSize = 11.sp,
                    color = MiuiRed,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (m.sideEffect.isNotBlank()) {
                Text(
                    text = if (expanded) "إخفاء الشرح" else "ماذا يحدث عند الإيقاف؟",
                    fontSize = 12.sp,
                    color = MiuiGreen,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable { expanded = !expanded }
                )
                if (expanded) {
                    MiuiDividerLine()
                    Text(
                        text = m.sideEffect,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MiuiTextSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * 标签配色。红 = 该关，橙 = 可关，绿 = 别乱关。
 * 用颜色而不是文字来传达「这个能不能动」，扫一眼就有判断。
 */
private fun behaviorTagColor(tag: String): Color = when (tag) {
    "开机自启", "持有唤醒锁" -> MiuiRed
    "频繁唤醒", "开放数据接口", "推送服务" -> MiuiOrange
    "无保护对外暴露", "解锁前运行", "独立进程" -> MiuiYellow
    else -> MiuiGreen  // 桌面入口 / 前台服务 / 深链：动了会出事
}

private fun typeLabel(type: ComponentType): String = when (type) {
    ComponentType.SERVICE -> "الخدمات"
    ComponentType.RECEIVER -> "المستقبِلات"
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
}*
 * 三件事要让用户看到：
 *   1. 正在跑（进度）
 *   2. 跑完了什么结果（走的哪条通道、多快、有没有走防火墙层）
 *   3. 后悔了怎么办（恢复按钮）
 *
 * 第 2 点里「有没有走防火墙层」值得单独说 —— 只有 PM 层的话，
 * 应用重启时能把自己 enable 回来，用户得知道这个区别。
 */
@Composable
private fun ApplyStatusBar(
    state: MainViewModel.ApplyState,
    dualLayer: Boolean,
    onDismiss: () -> Unit,
    onRestore: () -> Unit,
) {
    when (state) {
        is MainViewModel.ApplyState.Idle -> {
            if (!dualLayer) {
                Text(
                    "无 Root：冻结后应用可能自行恢复",
                    fontSize = 11.sp,
                    color = MiuiTextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }

        is MainViewModel.ApplyState.Running -> {
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    "正在冻结 ${state.done} / ${state.total}",
                    fontSize = 12.sp,
                    color = MiuiTextPrimary
                )
                LinearProgressIndicator(
                    progress = {
                        if (state.total == 0) 0f
                        else state.done.toFloat() / state.total
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp),
                    color = MiuiOrange,
                    trackColor = Color(0xFFEEEEEE),
                )
            }
        }

        is MainViewModel.ApplyState.Done -> {
            val r = state.result
            Column(modifier = Modifier.padding(top = 10.dp)) {
                Text(
                    r.describe(),
                    fontSize = 12.sp,
                    color = if (r.allOk) MiuiGreen else MiuiOrange
                )
                if (!r.dualLayer && r.succeeded > 0) {
                    Text(
                        "仅 PM 层生效，应用重启后可能自行恢复。装 Root 可启用防火墙层。",
                        fontSize = 11.sp,
                        lineHeight = 16.sp,
                        color = MiuiTextSecondary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text(
                        "知道了",
                        fontSize = 12.sp,
                        color = MiuiGreen,
                        modifier = Modifier.clickable { onDismiss() }
                    )
                    Text(
                        "全部恢复",
                        fontSize = 12.sp,
                        color = MiuiRed,
                        modifier = Modifier.clickable { onRestore() }
                    )
                }
            }
        }

        is MainViewModel.ApplyState.Failed -> {
            Text(
                state.message,
                fontSize = 12.sp,
                color = MiuiRed,
                modifier = Modifier
                    .padding(top = 10.dp)
                    .clickable { onDismiss() }
            )
        }
    }
}

/**
 * 单个组件卡片。
 *
 * 相比原来多了三样东西：
 *   1. 行为标签 —— 「开机自启」「推送服务」这种，比一串混淆类名有用得多；
 *   2. 不安全标记 —— 桌面入口/前台服务禁用后用户立刻能感知，得先警告；
 *   3. 点开看副作用 —— 默认收起，想知道「关了会怎样」再展开。
 */
@Composable
private fun ComponentRow(
    m: ComponentMetric,
    isBlocked: Boolean,
    onToggle: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val total = RiskScorer.combined(m)
    val tier = RiskScorerTierOf(total)
    val tierColor = tierColor(tier)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MiuiSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
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
                }
                Switch(
                    checked = isBlocked,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Color.White,
                        checkedTrackColor = if (m.safeToBlock) MiuiGreen else MiuiRed,
                        uncheckedThumbColor = Color.White,
                        uncheckedTrackColor = Color(0xFFE0E0E0)
                    )
                )
            }

            // 风险分 + 行为标签，横向可滚，标签多了不会挤爆
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MiuiBadge(text = "$total · ${tier.label}", color = tierColor)
                m.behaviorTags.forEach { tag ->
                    MiuiBadge(text = tag, color = behaviorTagColor(tag))
                }
            }

            if (!m.safeToBlock && m.behaviorTags.isNotEmpty()) {
                Text(
                    "禁用会影响可见功能，建议保留",
                    fontSize = 11.sp,
                    color = MiuiRed,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            if (m.sideEffect.isNotBlank()) {
                Text(
                    text = if (expanded) "收起说明" else "关了会怎样？",
                    fontSize = 12.sp,
                    color = MiuiGreen,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .clickable { expanded = !expanded }
                )
                if (expanded) {
                    MiuiDividerLine()
                    Text(
                        text = m.sideEffect,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = MiuiTextSecondary,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }
    }
}

/**
 * 标签配色。红 = 该关，橙 = 可关，绿 = 别乱关。
 * 用颜色而不是文字来传达「这个能不能动」，扫一眼就有判断。
 */
private fun behaviorTagColor(tag: String): Color = when (tag) {
    "开机自启", "持有唤醒锁" -> MiuiRed
    "频繁唤醒", "开放数据接口", "推送服务" -> MiuiOrange
    "无保护对外暴露", "解锁前运行", "独立进程" -> MiuiYellow
    else -> MiuiGreen  // 桌面入口 / 前台服务 / 深链：动了会出事
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
