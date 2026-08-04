package com.takahashirinta.ncrust.ui.i18n

import android.content.Context
import androidx.compose.runtime.compositionLocalOf

data class LanguagePreset(
    val code: String,
    val displayName: String,
    val strings: Strings
)

val languagePresets: List<LanguagePreset> = listOf(
    LanguagePreset("zh-CN", "简体中文", zhCN),
    LanguagePreset("zh-TW", "繁體中文", zhTW),
    LanguagePreset("en-US", "English", en),
    LanguagePreset("ja-JP", "日本語", jpJP),
    LanguagePreset("ja-MY", "万葉仮名", jpMY),
    LanguagePreset("ko-KP", "조선어", koNK),
    LanguagePreset("de-DE", "Deutsch", deDE),
    LanguagePreset("ru-RU", "Русский", ruRU),
)

val LocalStrings = compositionLocalOf { zhCN }

private const val PREFS_NAME = "ncrust_settings"
private const val KEY_LANGUAGE = "language_code"

fun getSavedLanguageCode(context: Context): String =
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_LANGUAGE, "zh-CN") ?: "zh-CN"

fun saveLanguageCode(context: Context, code: String) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_LANGUAGE, code).apply()
}

fun stringsForCode(code: String): Strings =
    languagePresets.find { it.code == code }?.strings
        // 旧版 "en-UK" 已并入 "en-US"，保留映射避免老用户语言设置静默回退中文
        ?: if (code == "en-UK") en else zhCN
