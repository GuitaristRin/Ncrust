package com.takahashirinta.ncrust.ui.theme

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color as ComposeColor

/**
 * 主题色预设条目
 * @param label 显示名称（中文）
 * @param color 主题色值
 */
data class ThemeColorPreset(
    val label: String,
    val color: Color
)

/**
 * 6 种主题色预设，契合设计文档第十三节：
 * Spruce / Cobalt / Crimson / Amber / Violet / Mono
 * 背景一律采用 OLED 纯黑 #000000（surface 统一用 #1A1A1A）
 */
val themeColorPresets = listOf(
    ThemeColorPreset("云杉",  Color(0xFF1DB954)),
    ThemeColorPreset("钴蓝",  Color(0xFF3B82F6)),
    ThemeColorPreset("绯红",  Color(0xFFEF4444)),
    ThemeColorPreset("琥珀",  Color(0xFFF59E0B)),
    ThemeColorPreset("堇紫",  Color(0xFF8B5CF6)),
    ThemeColorPreset("素白",  Color(0xFFFFFFFF)),
)

/** 主题模式：跟随系统 / 深色 / 浅色。 */
enum class ThemeMode { SYSTEM, DARK, LIGHT }

private const val PREFS_NAME = "ncrust_settings"
private const val KEY_THEME_INDEX = "theme_color_index"
private const val KEY_THEME_MODE = "theme_mode"

/** 读取已保存的主题模式，默认跟随系统。 */
fun getSavedThemeMode(context: Context): ThemeMode {
    val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_THEME_MODE, null)
    return runCatching { ThemeMode.valueOf(raw ?: "") }.getOrDefault(ThemeMode.SYSTEM)
}

fun saveThemeMode(context: Context, mode: ThemeMode) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_THEME_MODE, mode.name)
        .apply()
}

/**
 * 从 SharedPreferences 读取已保存的主题索引（默认 0 = 云杉）
 */
fun getSavedThemeIndex(context: Context): Int {
    return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getInt(KEY_THEME_INDEX, 0)
}

/**
 * 持久化主题索引（0..5）
 */
fun saveThemeIndex(context: Context, index: Int) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putInt(KEY_THEME_INDEX, index)
        .apply()
}

/**
 * 根据索引获取对应的 Color 预设，索引越界时兜底云杉
 */
fun themeColorForIndex(index: Int): Color {
    return themeColorPresets.getOrElse(index) { themeColorPresets[0] }.color
}

@Composable
fun NcrustTheme(
    primaryColor: Color = Color(0xFF1DB954),
    isDark: Boolean = true,
    content: @Composable () -> Unit
) {
    val colors = remember(primaryColor, isDark) {
        (if (isDark) DefaultNcrustColors else LightNcrustColors).copy(primary = primaryColor)
    }
    CompositionLocalProvider(
        LocalNcrustColors provides colors,
        LocalNcrustTypography provides DefaultNcrustTypography,
        content = content
    )
}

/**
 * 由主题色派生的输入框/搜索框底色：深色主题下是极暗的低饱和色，
 * 浅色主题下是极亮(近白)的低饱和色——保证框内文字(onBackground)始终可读。
 */
fun desaturateColor(
    color: ComposeColor,
    saturationFactor: Float = 0.08f,
    darkTheme: Boolean = true
): ComposeColor {
    val hsv = FloatArray(3)
    android.graphics.Color.colorToHSV(color.toArgb(), hsv)
    hsv[1] *= saturationFactor
    hsv[2] = if (darkTheme) hsv[2] * 0.25f + 0.02f else 0.93f
    return ComposeColor(android.graphics.Color.HSVToColor(hsv))
}

private fun ComposeColor.toArgb(): Int {
    return android.graphics.Color.argb(
        (alpha * 255).toInt(),
        (red * 255).toInt(),
        (green * 255).toInt(),
        (blue * 255).toInt()
    )
}
