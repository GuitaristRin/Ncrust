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
    LanguagePreset("en-US", "English (US)", enUS),
    LanguagePreset("en-UK", "English (UK)", enUK),
    LanguagePreset("ja-JP", "日本語", jpJP),
    LanguagePreset("ang-GB", "Ænglisc", angGB),
    LanguagePreset("en-1400", "Middle English", en1400),
    LanguagePreset("ko-KP", "조선어", koNK),
    LanguagePreset("de-DE", "Deutsch", deDE),
    LanguagePreset("ru-RU", "Русский", ruRU),
    LanguagePreset("ru-SU", "Советский русский", ruSU),
    LanguagePreset("el-GR", "Ελληνικά", elGR),
    LanguagePreset("la-VA", "Lingua Latina", laVA),
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
    languagePresets.find { it.code == code }?.strings ?: zhCN
