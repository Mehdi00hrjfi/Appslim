package com.appslim.analyzer.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// ---- MIUI / HyperOS 风格浅色色板 ----
val MiuiGreen        = Color(0xFF4CAF50)
val MiuiGreenLight   = Color(0xFFE8F5E9)
val MiuiBlue         = Color(0xFF2196F3)
val MiuiOrange       = Color(0xFFFF9800)
val MiuiRed          = Color(0xFFF44336)
val MiuiYellow       = Color(0xFFFFC107)

val MiuiBg           = Color(0xFFF7F8FA)
val MiuiSurface      = Color(0xFFFFFFFF)
val MiuiTextPrimary  = Color(0xFF212121)
val MiuiTextSecondary= Color(0xFF757575)
val MiuiDivider      = Color(0xFFF0F0F0)
val MiuiOutline      = Color(0xFFE0E0E0)

// 保留旧 token 以便其它模块不报错
val SlimGreen  = MiuiGreen
val SlimBlue   = MiuiBlue
val SlimOrange = MiuiOrange
val SlimRed    = MiuiRed
val SlimYellow = MiuiYellow

private val DarkScheme = darkColorScheme(
    primary = SlimGreen,
    secondary = SlimBlue,
    tertiary = SlimOrange,
    background = Color(0xFF0A0F1A),
    surface = Color(0xFF111A2A),
    surfaceVariant = Color(0xFF152034),
    onBackground = Color(0xFFE6EDF7),
    onSurface = Color(0xFFE6EDF7),
    outline = Color(0xFF1F2C44)
)

private val LightScheme = lightColorScheme(
    primary = MiuiGreen,
    onPrimary = Color.White,
    primaryContainer = MiuiGreenLight,
    onPrimaryContainer = Color(0xFF1B5E20),
    secondary = MiuiBlue,
    onSecondary = Color.White,
    tertiary = MiuiOrange,
    background = MiuiBg,
    onBackground = MiuiTextPrimary,
    surface = MiuiSurface,
    onSurface = MiuiTextPrimary,
    surfaceVariant = Color(0xFFF5F5F5),
    onSurfaceVariant = MiuiTextSecondary,
    outline = MiuiOutline,
    error = MiuiRed,
    onError = Color.White,
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = Color(0xFFB71C1C)
)

@Composable
fun AppSlimTheme(
    darkTheme: Boolean = false, // MIUI X 默认浅色
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme || isSystemInDarkTheme()) DarkScheme else LightScheme,
        content = content
    )
}
