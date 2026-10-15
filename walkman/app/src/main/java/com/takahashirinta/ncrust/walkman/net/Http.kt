package com.takahashirinta.ncrust.walkman.net

import okhttp3.ConnectionPool
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * 全进程一份 OkHttpClient。
 *
 * 复用一份而不是每个接口各建一个：OkHttp 的连接池与线程池是**每 client 一份**，
 * 多建一个就多一组线程和一组空闲连接，而本机的单应用内存上限只有 48 MB
 * （`docs/f885-spike-report.md` §1），这类"看不见的常驻开销"正是要压的。
 *
 * `ConnectionSpec.MODERN_TLS` 而不是默认值：默认值是 `[MODERN_TLS, CLEARTEXT]`，
 * 这里显式只留 MODERN_TLS（TLS 1.2+，ECDHE + AEAD 套件），把"降级到 TLS 1.0"这条路封死
 * —— 否则哪天服务端回落到 TLS 1.0，我们会以为一切正常却拿不到数据。
 * 明文 HTTP 不经过 OkHttp（音频 URL 交给 MediaPlayer），所以去掉 CLEARTEXT 不影响播放。
 */
object Http {

    /** 网页/接口侧身份（与主工程 `RetrofitClient.UA` 同形）。 */
    const val PC_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Safari/537.36"

    /** xeapi 要求的官方 Android 客户端 UA（`spec/api/xeapi.md` §1，逐字照抄，别改）。 */
    const val ANDROID_UA =
        "NeteaseMusic/9.5.61.260802021928(9005061);Dalvik/2.1.0 (Linux; U; Android 12; " +
            "HBN-AL00 Build/cd737a2.0)"

    /**
     * 登录 cookie 串。W0 允许为空（匿名），由上层注入；W1 接持久化。
     *
     * 放这里而不是 OkHttp 的 CookieJar：网易的登录态是一整串非标准 cookie，
     * 上层（WebView / 扫码 / 局域网交接）拿到的就是字符串，来回转换只会丢字段。
     */
    @Volatile
    var cookie: String? = null

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 本次请求实际协商到的 TLS 版本，冒烟界面要显示它（"真的走了 TLS 1.2 吗"）。
     *
     * 必须写成方法调用 `handshake()` / `tlsVersion()` / `javaName()`：OkHttp 3.12 是 Java 写的，
     * 同名私有/包私有字段会被 Kotlin 当成属性优先解析，写成属性语法会报
     * "Cannot access 'handshake': it is package-private"。
     */
    fun tlsVersionOf(response: Response): String {
        val handshake = response.handshake() ?: return "<no handshake>"
        return handshake.tlsVersion().javaName()
    }

    /** 从 cookie 串里取一个键的值；没有返回 null。 */
    fun cookieValue(name: String): String? {
        val raw = cookie ?: return null
        for (part in raw.split(';')) {
            val trimmed = part.trim()
            val eq = trimmed.indexOf('=')
            if (eq > 0 && trimmed.substring(0, eq) == name) {
                return trimmed.substring(eq + 1)
            }
        }
        return null
    }
}
