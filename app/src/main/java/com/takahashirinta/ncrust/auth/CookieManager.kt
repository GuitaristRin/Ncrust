package com.takahashirinta.ncrust.auth

import android.content.Context
import android.content.SharedPreferences

object CookieManager {
    private const val PREFS_NAME = "ncrust_prefs"
    private const val KEY_COOKIE = "user_cookie"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveCookie(context: Context, cookie: String) {
        getPrefs(context).edit().putString(KEY_COOKIE, cookie).apply()
    }

    fun getCookie(context: Context): String? {
        return getPrefs(context).getString(KEY_COOKIE, null)
    }

    fun hasCookie(context: Context): Boolean {
        return !getCookie(context).isNullOrBlank()
    }

    fun clearCookie(context: Context) {
        getPrefs(context).edit().remove(KEY_COOKIE).apply()
    }
}
