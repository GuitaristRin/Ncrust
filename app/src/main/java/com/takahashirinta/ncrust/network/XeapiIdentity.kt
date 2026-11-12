package com.takahashirinta.ncrust.network

import android.content.res.Resources
import android.os.Build
import java.net.URLEncoder

/**
 * **xeapi（Android 身份）** 的自洽指纹：UA、`x-appver`、cookie `appver`、公钥请求的
 * `appVersion` 同源，`x-osver` / cookie `osver` 取真实系统版本。
 *
 * 与 [ClientIdentity] 的分工：那个是 **eapi 的 PC 身份**（写歌单、取链），这里是
 * **xeapi 的 Android 身份**（收藏写入、公钥handshake）。两套身份不能混用。
 *
 * 为什么必须同源：官方 Android 客户端里 UA 由 `NeteaseMusic/<版本>(<buildNo>);<系统 http.agent>`
 * 现拼（`y22/b.java`），`x-appver` / cookie `appver` 来自同一个版本号，`x-osver` 取真实系统
 * （`icustomconfig/j0.java`、`AbsCookieStore.initDevicesCookie`）。真实客户端不会出现
 * 「UA 说 9.5.61、头说 9.1.65、系统说 Android 12」这种组合；收藏被服务端判成
 * 「当前环境异常，已取消喜欢」（524）时，这是首要可疑信号（spec/api/xeapi.md §3.3）。
 *
 * 依据：官方 9.6.22 反编译 `y22/b.java`、`icustomconfig/j0.java`、`AbsCookieStore`。
 */
internal object XeapiIdentity {

    /** 唯一版本来源：UA、`x-appver`、cookie `appver`、公钥请求的 `appVersion` 都用它。 */
    const val APP_VERSION = "9.5.61"

    /** 官方版本串的构建后缀与 build 号，观测自 9.5.61 真机 UA（spec/api/xeapi.md §3.3）。 */
    private const val APP_VERSION_SUFFIX = "260802021928"
    private const val BUILD_NO = "9005061"

    /** `x-osver` / cookie `osver`：真实系统版本；取不到时按官方 `validateAndEncode` 回落 unknown。 */
    val osVersion: String = Build.VERSION.RELEASE?.trim().orEmpty().ifEmpty { "unknown" }

    /**
     * 官方 UA 形态：`NeteaseMusic/<版本>.<构建后缀>(<buildNo>);<系统 http.agent>`。
     * 后半段直接取系统 `http.agent`，机型与 Android 版本因此天然和 `x-osver` 一致。
     */
    val userAgent: String = buildString {
        append("NeteaseMusic/")
        append(APP_VERSION)
        append('.')
        append(APP_VERSION_SUFFIX)
        append('(')
        append(BUILD_NO)
        append(");")
        append(System.getProperty("http.agent") ?: "Dalvik/2.1.0 (Linux; U; Android $osVersion)")
    }

    /**
     * 官方 `AbsCookieStore.initDevicesCookie` 里与设备绑定的 cookie。
     *
     * 只补语义明确的四项：机型、品牌、分辨率、versioncode。`channel` / `packageType` /
     * `screenType` 是网易内部取值，猜一个只会制造新的不一致，宁可不发。
     */
    fun deviceCookies(): Map<String, String> = linkedMapOf(
        "mobilename" to encode(Build.MODEL),
        "brand" to encode(Build.BRAND),
        "resolution" to resolution(),
        "versioncode" to BUILD_NO
    )

    private fun resolution(): String = runCatching {
        val metrics = Resources.getSystem().displayMetrics
        "${metrics.widthPixels}x${metrics.heightPixels}"
    }.getOrDefault("unknown")

    /** 官方 `validateAndEncode`：去掉控制字符后按 UTF-8 URL 编码，空值回落 unknown。 */
    private fun encode(raw: String?): String {
        val value = raw?.filter { it.code > 32 }?.trim().orEmpty()
        if (value.isEmpty()) return "unknown"
        return runCatching { URLEncoder.encode(value, "UTF-8") }.getOrDefault(value)
    }
}
