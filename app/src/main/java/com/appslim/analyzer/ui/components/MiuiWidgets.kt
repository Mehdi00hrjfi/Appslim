package com.appslim.analyzer.ui.components

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.appslim.analyzer.ui.theme.MiuiSurface
import com.appslim.analyzer.ui.theme.MiuiDivider
import com.appslim.analyzer.ui.theme.MiuiGreen
import com.appslim.analyzer.ui.theme.MiuiGreenLight
import com.appslim.analyzer.ui.theme.MiuiTextPrimary
import com.appslim.analyzer.ui.theme.MiuiTextSecondary

/** 页面容器：背景已由 Scaffold 绘制，这里只负责水平内边距，避免重复绘制造成 overdraw */
@Composable
fun MiuiPageBg(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        content()
    }
}

/** 20dp 圆角白色卡片（MIUI 风格，无阴影） */
@Composable
fun MiuiCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MiuiSurface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

/** 绿色/灰色状态卡片（LSPosed 概览页风格） */
@Composable
fun MiuiStatusCard(
    title: String,
    subtitle: String,
    isActive: Boolean,
    modifier: Modifier = Modifier
) {
    val bg = if (isActive) MiuiGreenLight else Color(0xFFF5F5F5)
    val accent = if (isActive) MiuiGreen else MiuiTextSecondary
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = bg
    ) {
        Row(
            modifier = Modifier.padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = accent
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    fontSize = 14.sp,
                    color = accent.copy(alpha = 0.8f)
                )
            }
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = accent.copy(alpha = 0.25f)
            )
        }
    }
}

/** 标题 + 副文 信息行 */
@Composable
fun MiuiInfoRow(title: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = title, fontSize = 15.sp, color = MiuiTextPrimary)
        Text(
            text = value,
            fontSize = 14.sp,
            color = MiuiTextSecondary,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 右侧带箭头的可点击行 */
@Composable
fun MiuiArrowRow(
    title: String,
    subtitle: String? = null,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(vertical = 12.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, fontSize = 15.sp, color = MiuiTextPrimary)
                if (!subtitle.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(text = subtitle, fontSize = 13.sp, color = MiuiTextSecondary)
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MiuiTextSecondary,
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

/** 卡片内分组小标题 */
@Composable
fun MiuiSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier.padding(bottom = 8.dp),
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold,
        color = MiuiTextPrimary
    )
}

/** 列表项分隔线 */
@Composable
fun MiuiDividerLine(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MiuiDivider)
    )
}

/** 彩色小徽章 */
@Composable
fun MiuiBadge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(text = text, fontSize = 12.sp, color = color, fontWeight = FontWeight.Medium)
    }
}

/**
 * 应用图标：
 * - 有真实图标时直接展示（不套绿色圆形底色，保持应用原始形状）
 * - 无图标时才用绿色圆形占位 + Apps 图标
 */
@Composable
fun AppIcon(
    bitmap: android.graphics.Bitmap?,
    size: androidx.compose.ui.unit.Dp,
    placeholderSize: androidx.compose.ui.unit.Dp = size * 0.6f,
    modifier: Modifier = Modifier
) {
    if (bitmap != null) {
        androidx.compose.foundation.Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier.size(size)
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(CircleShape)
                .background(MiuiGreenLight),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Apps,
                contentDescription = null,
                tint = MiuiGreen,
                modifier = Modifier.size(placeholderSize)
            )
        }
    }
}
