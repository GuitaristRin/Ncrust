package com.takahashirinta.ncrust.auth

import android.content.Context
import android.content.SharedPreferences

object CookieManager {
    private const val PREFS_NAME = "ncrust_prefs"
    private const val KEY_COOKIE = "user_cookie"
    private const val KEY_MUSIC_U = "music_u"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveCookie(context: Context, cookie: String) {
        getPrefs(context).edit().putString(KEY_COOKIE, cookie).apply()
        // 同时提取 MUSIC_U 方便快速检查
        val musicU = cookie.split(";")
            .map { it.trim() }
            .firstOrNull { it.startsWith("MUSIC_U=") }
            ?.removePrefix("MUSIC_U=")
        if (musicU != null) {
            getPrefs(context).edit().putString(KEY_MUSIC_U, musicU).apply()
        }
    }

    fun getCookie(context: Context): String? {
        return getPrefs(context).getString(KEY_COOKIE, null)
    }

    fun getMusicU(context: Context): String? {
        return getPrefs(context).getString(KEY_MUSIC_U, null)
    }

    fun hasCookie(context: Context): Boolean {
        return !getCookie(context).isNullOrBlank()
    }

    fun clearCookie(context: Context) {
        getPrefs(context).edit().remove(KEY_COOKIE).remove(KEY_MUSIC_U).apply()
    }

    fun getCookieInfo(context: Context): CookieInfo {
        val cookie = getCookie(context)
        val musicU = getMusicU(context)
        return CookieInfo(
            hasCookie = !cookie.isNullOrBlank(),
            musicU = musicU,
            cookieLength = cookie?.length ?: 0,
            cookiePreview = if (cookie != null && cookie.length > 50) {
                cookie.take(50) + "..."
            } else {
                cookie ?: ""
            }
        )
    }
}

data class CookieInfo(
    val hasCookie: Boolean,
    val musicU: String?,
    val cookieLength: Int,
    val cookiePreview: String
)
