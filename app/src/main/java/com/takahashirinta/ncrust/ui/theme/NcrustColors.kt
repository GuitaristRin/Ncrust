package com.takahashirinta.ncrust.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * Ncrust 自有配色板。替代 MD3 的 ColorScheme。
 *
 * 设计：OLED 纯黑背景 + 单一强调色 + 中性灰未选中态。
 * 所有颜色硬编码，不依赖 MD3 的 tonalElevation 调色逻辑。
 */
@Immutable
data class NcrustColors(
    val primary: Color,
    val background: Color,
    val surface: Color,
    val surfaceVariant: Color,
    val onBackground: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color
)

/**
 * 默认配色：云杉绿主题 + OLED 纯黑。
 * onSurfaceVariant 用中性灰 #B3B3B3（Spotify 风），与 MD3 紫调 onSurfaceVariant 区分。
 */
val DefaultNcrustColors = NcrustColors(
    primary = Color(0xFF1DB954),
    background = Color(0xFF000000),
    surface = Color(0xFF1A1A1A),
    surfaceVariant = Color(0xFF1A1A1A),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFB3B3B3)
)

val LocalNcrustColors = compositionLocalOf { DefaultNcrustColors }
