package com.appslim.analyzer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Phishing
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appslim.analyzer.util.CrashLogger
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.appslim.analyzer.lens.LensViewModel
import com.appslim.analyzer.lens.ui.LensScreen
import com.appslim.analyzer.ui.MainViewModel
import com.appslim.analyzer.ui.screens.AppDetailScreen
import com.appslim.analyzer.ui.screens.AppListScreen
import com.appslim.analyzer.ui.screens.DashboardScreen
import com.appslim.analyzer.ui.screens.ReportScreen
import com.appslim.analyzer.ui.screens.RulesScreen
import com.appslim.analyzer.ui.theme.AppSlimTheme
import com.appslim.analyzer.ui.theme.MiuiGreen

sealed class Dest(val route: String, val label: String, val icon: ImageVector) {
    data object Dashboard : Dest("dashboard", "总览", Icons.Filled.Dashboard)
    data object Apps : Dest("apps", "应用", Icons.Filled.Apps)
    data object Rules : Dest("rules", "规则", Icons.Filled.Phishing)
    data object Report : Dest("report", "报告", Icons.Filled.Description)
    companion object {
        val tabs = listOf(Dashboard, Apps, Rules, Report)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppSlimTheme {
                val vm: MainViewModel = viewModel()
                AppNav(vm)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNav(vm: MainViewModel) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val current = backStack?.destination?.route
    var showCrash by remember { mutableStateOf(false) }
    val context = LocalContext.current

    if (showCrash) {
        val log = remember { CrashLogger.read() }
        AlertDialog(
            onDismissRequest = { showCrash = false },
            title = { Text("崩溃日志", fontSize = 16.sp, fontWeight = FontWeight.Bold) },
            text = {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        log,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("crash", CrashLogger.read()))
                    showCrash = false
                }) { Text("复制全部") }
            },
            dismissButton = {
                TextButton(onClick = { CrashLogger.clear(); showCrash = false }) { Text("清空") }
            }
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            androidx.compose.material3.TopAppBar(
                title = { Text("AppSlim Analyzer", fontSize = 17.sp, fontWeight = FontWeight.Bold) },
                actions = {
                    TextButton(onClick = { showCrash = true }) { Text("崩溃日志") }
                }
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = Color.White,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp
            ) {
                Dest.tabs.forEach { d ->
                    val selected = current == d.route
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(d.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(d.icon, contentDescription = d.label) },
                        label = { Text(d.label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MiuiGreen,
                            selectedTextColor = MiuiGreen,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Dest.Dashboard.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Dest.Dashboard.route) { DashboardScreen(vm) }
            composable(Dest.Apps.route) {
                AppListScreen(vm, onOpenApp = { pkg ->
                    vm.selectApp(pkg)
                    nav.navigate("detail")
                })
            }
            composable("detail") { backStack ->
                val pkg = backStack.arguments?.getString("pkg").orEmpty()
                AppDetailScreen(
                    vm,
                    onBack = { nav.popBackStack() },
                    onLens = { p -> vm.selectApp(p); nav.navigate("lens?pkg=$p") }
                )
            }
            composable(
                route = "lens",
                arguments = listOf(navArgument("pkg") { defaultValue = "" })
            ) { backStack ->
                val pkg = backStack.arguments?.getString("pkg").orEmpty()
                LensScreen(vm = viewModel<LensViewModel>(), pkg = pkg, onBack = { nav.popBackStack() })
            }
            composable(Dest.Rules.route) { RulesScreen(vm) }
            composable(Dest.Report.route) { ReportScreen(vm) }
        }
    }
}
