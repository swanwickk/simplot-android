package com.simplot.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 居家战术色板：深蓝-青色的"指挥甲板"（比青绿更贴海战，夜晚暖光下不刺眼）
// 浅色仍保留高对比可读，夜深可切系统深色；深色拉高灰度层次，海图为绝对主角
private val LightColors = lightColorScheme(
    primary = Color(0xFF0B3A5A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD6E8F5),
    onPrimaryContainer = Color(0xFF0B2233),
    secondary = Color(0xFF2E6B8A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFDCEEF7),
    onSecondaryContainer = Color(0xFF0F2433),
    tertiary = Color(0xFF7A5A2E),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFF5E6C8),
    onTertiaryContainer = Color(0xFF2B1E0B),
    background = Color(0xFFF4F6F9),
    onBackground = Color(0xFF16202A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF16202A),
    surfaceVariant = Color(0xFFE8EEF4),
    onSurfaceVariant = Color(0xFF3A4A5C),
    outline = Color(0xFF7A8AA0),
    outlineVariant = Color(0xFFCCD6E3),
    scrim = Color(0xFF0B2233),
    error = Color(0xFF8B1E1E),
    onError = Color(0xFFFFFFFF)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FC4E8),
    onPrimary = Color(0xFF0B2233),
    primaryContainer = Color(0xFF123A52),
    onPrimaryContainer = Color(0xFFD6E8F5),
    secondary = Color(0xFF8BC9E5),
    onSecondary = Color(0xFF0F2433),
    secondaryContainer = Color(0xFF1A3D4F),
    onSecondaryContainer = Color(0xFFDCEEF7),
    tertiary = Color(0xFFE0C18A),
    onTertiary = Color(0xFF2B1E0B),
    tertiaryContainer = Color(0xFF5A4422),
    onTertiaryContainer = Color(0xFFF5E6C8),
    background = Color(0xFF0F141A),
    onBackground = Color(0xFFE6ECF2),
    surface = Color(0xFF141A22),
    onSurface = Color(0xFFE6ECF2),
    surfaceVariant = Color(0xFF1E2A36),
    onSurfaceVariant = Color(0xFF9AA9BE),
    outline = Color(0xFF6E7E95),
    outlineVariant = Color(0xFF2A3A4D),
    scrim = Color(0xFF000000),
    error = Color(0xFFE57373),
    onError = Color(0xFF3B0A0A)
)

@Composable
fun SimPlotTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content
    )
}
