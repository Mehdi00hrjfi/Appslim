package com.appslim.analyzer.ui.screens

import android.widget.Toast
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appslim.analyzer.ui.MainViewModel
import com.appslim.analyzer.ui.components.MiuiCard
import com.appslim.analyzer.ui.components.MiuiDividerLine
import com.appslim.analyzer.ui.components.MiuiInfoRow
import com.appslim.analyzer.ui.components.MiuiPageBg
import com.appslim.analyzer.ui.components.MiuiSectionTitle
import com.appslim.analyzer.ui.theme.MiuiGreen
import com.appslim.analyzer.ui.theme.MiuiSurface
import com.appslim.analyzer.ui.theme.MiuiTextPrimary
import com.appslim.analyzer.ui.theme.MiuiTextSecondary

/** Hook 规则：MIUI 卡片化 rules.json 预览 / 打包（LazyColumn 滚动优化） */
@Composable
fun RulesScreen(vm: MainViewModel) {
    val json by vm.rulesJson.collectAsState()
    val context = LocalContext.current

    MiuiPageBg {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Hook 规则",
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold,
                    color = MiuiTextPrimary
                )
                Text(
                    text = "生成 LSPosed 模块用的阻断规则",
                    fontSize = 13.sp,
                    color = MiuiTextSecondary
                )
            }

            item {
                MiuiCard {
                    MiuiSectionTitle("Hook 点说明")
                    MiuiInfoRow("Activity", "Instrumentation.execStartActivity()")
                    MiuiDividerLine()
                    MiuiInfoRow("Service", "startService() / bindService()")
                    MiuiDividerLine()
                    MiuiInfoRow("Provider", "ContentProvider.attachInfo()")
                    MiuiDividerLine()
                    MiuiInfoRow("白名单机制", "核心组件永不阻断，防止闪退")
                }
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFF5F5F5)),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Text(
                        text = if (json.isBlank()) "{\n  // 请先在「应用」详情中选择要阻断的组件\n}"
                              else json,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = MiuiTextPrimary,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(
                        onClick = {
                            vm.packModule()?.let {
                                Toast.makeText(context, "已生成：${it.absolutePath}", Toast.LENGTH_LONG).show()
                            } ?: Toast.makeText(context, "阻断名单为空", Toast.LENGTH_SHORT).show()
                        },
                        enabled = json.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) { Text("📦 打包模块", fontSize = 14.sp) }
                    Button(
                        onClick = {
                            val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                                as android.content.ClipboardManager
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("rules.json", json))
                            Toast.makeText(context, "已复制 rules.json", Toast.LENGTH_SHORT).show()
                        },
                        enabled = json.isNotBlank(),
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MiuiGreen)
                    ) { Text("复制 JSON", fontSize = 14.sp) }
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}
