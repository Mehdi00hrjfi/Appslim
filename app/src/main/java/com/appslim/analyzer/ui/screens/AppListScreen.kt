package com.appslim.analyzer.ui.screens

import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appslim.analyzer.ui.MainViewModel
import com.appslim.analyzer.ui.MainViewModel.AppFilter
import com.appslim.analyzer.ui.MainViewModel.AppItem
import com.appslim.analyzer.ui.MainViewModel.AppSort
import com.appslim.analyzer.ui.components.MiuiBadge
import com.appslim.analyzer.ui.components.MiuiCard
import com.appslim.analyzer.ui.components.MiuiPageBg
import com.appslim.analyzer.ui.components.AppIcon
import com.appslim.analyzer.ui.theme.MiuiSurface
import com.appslim.analyzer.ui.theme.MiuiGreen
import com.appslim.analyzer.ui.theme.MiuiGreenLight
import com.appslim.analyzer.ui.theme.MiuiOrange
import com.appslim.analyzer.ui.theme.MiuiRed
import com.appslim.analyzer.ui.theme.MiuiTextPrimary
import com.appslim.analyzer.ui.theme.MiuiTextSecondary

/** 应用列表：MIUI 卡片化列表 + 搜索 + 权限底部弹窗 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppListScreen(vm: MainViewModel, onOpenApp: (String) -> Unit) {
    val apps by vm.apps.collectAsState()
    val perms by vm.perms.collectAsState()
    val usageGranted by vm.usageGranted.collectAsState()
    val search by vm.search.collectAsState()
    val list by vm.filteredApps.collectAsState()

    var showUsageSheet by remember { mutableStateOf(false) }
    var showRootDialog by remember { mutableStateOf(false) }

    val usageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { vm.refreshPermissions() }
    fun openUsageSettings() =
        usageLauncher.launch(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))

    LaunchedEffect(Unit) { if (!usageGranted) showUsageSheet = true }
    // 一旦获得 Root / 应用列表权限（如首页授权后返回），自动收起授权弹窗
    LaunchedEffect(usageGranted) { if (usageGranted) showUsageSheet = false }

    MiuiPageBg {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "已安装应用",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MiuiTextPrimary
            )
            Text(
                text = "共 ${apps.size} 个 · 点击应用可扫描其组件",
                fontSize = 13.sp,
                color = MiuiTextSecondary
            )

            // 筛选：全部 / 应用 / 系统
            FilterChips(
                current = vm.appFilter.value,
                onSelect = { vm.appFilter.value = it }
            )

            // 排序：名称 / 可瘦身 / 最近更新 / 最近使用（最近使用需「使用情况访问」授权）
            SortChips(
                current = vm.appSort.value,
                showRecent = usageGranted,
                onSelect = { vm.appSort.value = it }
            )

            // Root 提示
            if (!perms.root && !perms.shizuku) {
                HintBanner(
                    color = MiuiRed,
                    title = "未检测到 Root / Shizuku",
                    body = "阻断、Hook、后台服务分析等高级功能将受限。",
                    action = "如何获取 Root？"
                ) { showRootDialog = true }
            }

            // Usage 权限提示
            if (!usageGranted) {
                HintBanner(
                    color = MiuiOrange,
                    title = "需要「应用列表权限」",
                    body = "授予「使用情况访问」后，AppSlim 才能扫描本机应用。",
                    action = "去授权"
                ) { openUsageSettings() }
            }

            // 搜索框
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MiuiSurface),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                TextField(
                    value = search,
                    onValueChange = { vm.search.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索应用名称或包名…", color = MiuiTextSecondary) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = MiuiTextSecondary) },
                    singleLine = true,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )
            }

            if (list.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(top = 36.dp), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (apps.isEmpty()) "正在扫描已安装应用…（请先授权应用列表权限）"
                               else "未找到匹配「$search」的应用",
                        color = MiuiTextSecondary,
                        fontSize = 14.sp
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(list, key = { it.pkg }) { app ->
                        AppItemCard(
                            vm = vm,
                            app = app,
                            onClick = { onOpenApp(app.pkg) }
                        )
                    }
                }
            }
        }
    }

    // 应用列表权限底部询问弹窗
    if (showUsageSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showUsageSheet = false },
            sheetState = sheetState,
            dragHandle = null,
            containerColor = MiuiSurface
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp)
            ) {
                Text(
                    "需要「应用列表权限」",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuiTextPrimary
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "允许后，AppSlim 才能扫描并分析本机已安装应用的运行组件。",
                    fontSize = 14.sp,
                    color = MiuiTextSecondary
                )
                Spacer(Modifier.height(24.dp))

                Button(
                    onClick = { showUsageSheet = false; openUsageSettings() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MiuiGreen)
                ) {
                    Text("始终允许", fontSize = 15.sp)
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { showUsageSheet = false; openUsageSettings() },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF5F5F5),
                        contentColor = MiuiTextPrimary
                    )
                ) {
                    Text("本次使用允许", fontSize = 15.sp)
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { showUsageSheet = false },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("拒绝", fontSize = 15.sp)
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Root 获取方法弹窗
    if (showRootDialog) {
        AlertDialog(
            onDismissRequest = { showRootDialog = false },
            containerColor = MiuiSurface,
            title = { Text("如何获取 Root 权限") },
            text = {
                Text(
                    "方案一 · 获取 Root（获得全部能力）：\n" +
                    "1. 解锁 Bootloader（各机型方法不同，请自行查阅）\n" +
                    "2. 刷入 Magisk / KernelSU / APatch 任一框架\n" +
                    "3. 在框架中启用 AppSlim，并在 LSPosed 勾选目标应用\n\n" +
                    "方案二 · 无 Root 替代（Shizuku）：\n" +
                    "通过「无线调试」激活 Shizuku 即可获得大部分数据采集能力，" +
                    "无需解锁或 Root。"
                )
            },
            confirmButton = {
                Button(
                    onClick = { showRootDialog = false; vm.refreshPermissions() },
                    colors = ButtonDefaults.buttonColors(containerColor = MiuiGreen)
                ) { Text("重新检测 Root") }
            },
            dismissButton = { TextButton(onClick = { showRootDialog = false }) { Text("我知道了") } }
        )
    }
}

/** 顶部筛选条：全部 / 应用（用户应用）/ 系统 */
@Composable
private fun FilterChips(current: AppFilter, onSelect: (AppFilter) -> Unit) {
    val items = listOf(
        AppFilter.ALL to "全部",
        AppFilter.USER to "应用",
        AppFilter.SYSTEM to "系统"
    )
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { (filter, label) ->
            val selected = current == filter
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) MiuiGreen else MiuiGreenLight)
                    .clickable { onSelect(filter) }
                    .padding(horizontal = 18.dp, vertical = 9.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) Color.White else MiuiGreen
                )
            }
        }
    }
}

@Composable
private fun AppItemCard(vm: MainViewModel, app: AppItem, onClick: () -> Unit) {
    val meta = vm.metaOf(app.pkg)
    // 图标异步：仅本卡片可见时才解码，命中 IconCache 内存后直接复用
    var icon by remember(app.pkg) { mutableStateOf(vm.iconFor(app.pkg)) }
    LaunchedEffect(app.pkg) {
        if (icon == null) icon = vm.loadIcon(app.pkg, app.apkPath)
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MiuiSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AppIcon(
                bitmap = icon,
                size = 44.dp,
                placeholderSize = 24.dp
            )
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(app.label, fontWeight = FontWeight.Bold, fontSize = 15.sp, color = MiuiTextPrimary)
                Text(
                    app.pkg,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MiuiTextSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
            val comp = meta?.compCount
            val bg = meta?.backgroundCount ?: 0
            MiuiBadge(
                text = when {
                    comp != null -> "$comp 组件" + if (bg > 0) " · $bg 后台" else ""
                    else -> "点击扫描"
                },
                color = when {
                    comp == null -> MiuiTextSecondary
                    bg >= 10 -> MiuiOrange      // 后台组件多的，优先瘦身提示
                    else -> MiuiGreen
                }
            )
        }
    }
}

/** 排序条：名称 / 可瘦身优先 / 最近更新 / 最近使用（最近使用视权限条件显示） */
@Composable
private fun SortChips(current: AppSort, showRecent: Boolean, onSelect: (AppSort) -> Unit) {
    val items = buildList {
        add(AppSort.NAME to "名称")
        add(AppSort.SLIM to "可瘦身")
        add(AppSort.UPDATED to "最近更新")
        if (showRecent) add(AppSort.RECENT to "最近使用")
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        items.forEach { (sort, label) ->
            val selected = current == sort
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(if (selected) MiuiGreenLight else Color(0xFFF2F2F2))
                    .clickable { onSelect(sort) }
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) MiuiGreen else MiuiTextSecondary
                )
            }
        }
    }
}

@Composable
private fun HintBanner(
    color: Color,
    title: String,
    body: String,
    action: String,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.10f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Warning,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = color)
                Text(body, fontSize = 12.sp, color = MiuiTextSecondary, modifier = Modifier.padding(top = 2.dp))
            }
            Spacer(Modifier.size(8.dp))
            TextButton(onClick = onClick) { Text(action, color = color) }
        }
    }
}
