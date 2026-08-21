package com.takahashirinta.ncrust.network

import android.content.Context
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.crypto.EapiCrypto
import com.takahashirinta.ncrust.network.crypto.WeapiCrypto
import okhttp3.*
import org.json.JSONObject
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val BASE_URL = "https://music.163.com"
    private const val INTERFACE_URL = "https://interface3.music.163.com"
    // eapi 客户端接口域：官方/参考实现全部 eapi 打到 interface.music.163.com（非 music.163.com）。
    // 读接口在 music 上宽松可通,但写操作(如 /eapi/radio/like)发错 host 会被判 -460 风险。
    private const val API_URL = "https://interface.music.163.com"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val IOS_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"

    private var currentCookie: String? = null

    fun init(context: Context) {
        currentCookie = CookieManager.getCookie(context)
    }

    fun updateCookie(cookie: String?) {
        currentCookie = cookie
    }

    fun getCookie(): String? = currentCookie

    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    val api: NcmApi by lazy {
        // BASIC 日志只在 debug 装：release 每次请求省一次 chain.proceed 拦截 + logcat 序列化。
        // 低端机上 CPU 敏感，能省则省。
        val client = OkHttpClient.Builder().apply {
            if (BuildConfig.DEBUG) {
                addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BASIC
                })
            }
            addInterceptor(CookieInterceptor())
            connectTimeout(30, TimeUnit.SECONDS)
            readTimeout(30, TimeUnit.SECONDS)
        }.build()

        Retrofit.Builder()
            .baseUrl("$BASE_URL/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(NcmApi::class.java)
    }

    fun eapiPost(
        path: String,
        payload: Map<String, String>,
        useInterface: Boolean = false
    ): Response {        val host = if (useInterface) INTERFACE_URL else API_URL
        val fullUrl = host + path
        val anyPayload = payload.mapValues { it.value as Any }
        val params = EapiCrypto.encryptParams(fullUrl, anyPayload)

        val requestBody = FormBody.Builder()
            .add("params", params)
            .build()

        val request = Request.Builder()
            .url(fullUrl)
            .post(requestBody)
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", currentCookie ?: "")
            .build()

        return plainClient.newCall(request).execute()
    }

    /**
     * 逐行对齐官方/参考实现的 eapi 请求：
     *  - 发到 interface.music.163.com/eapi/...
     *  - 设备字段(osver/deviceId/os/appver/.../__csrf/MUSIC_U)以 **Cookie** 形式发送
     *    (createHeaderCookie 同款),非独立 HTTP 头
     *  - iOS UA；data.header 同时写入加密 body
     * 用于写接口(如 /eapi/radio/like)以彻底对齐客户端指纹,排除 -460 风控的字段形态差异。
     */
    fun eapiPostOfficial(path: String, payload: Map<String, String>): Response {
        val fullUrl = API_URL + path
        val csrf = getCsrfToken().orEmpty()
        val mus: Map<String, String> = (currentCookie ?: "")
            .split(';')
            .asSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { it.split('=', limit = 2) }
            .associate { it[0].trim() to it.getOrElse(1) { "" } }
        val header = LinkedHashMap<String, String>()
        header["os"] = "iphone"
        header["appver"] = "8.9.60"
        header["deviceId"] = mus["deviceId"] ?: run {
            (0 until 20).joinToString("") { "0123456789abcdef"[(Math.random() * 16).toInt()].toString() }
        }
        header["osver"] = mus["osver"] ?: "16.0"
        header["versioncode"] = "140"
        header["mobilename"] = ""
        header["buildver"] = (System.currentTimeMillis() / 1000).toString().take(10)
        header["resolution"] = "1920x1080"
        header["__csrf"] = csrf
        header["channel"] = "yykj"
        header["requestId"] = (20_000_000..30_000_000).random().toString()
        mus["MUSIC_U"]?.let { header["MUSIC_U"] = it }
        mus["MUSIC_A"]?.let { header["MUSIC_A"] = it }

        val cookieStr = header.entries.joinToString(";") { (k, v) ->
            java.net.URLEncoder.encode(k, "UTF-8") + "=" + java.net.URLEncoder.encode(v, "UTF-8")
        }

        val data = payload.toMutableMap()
        val headerJson = JSONObject()
        header.forEach { (k, v) -> headerJson.put(k, v) }
        data["header"] = headerJson.toString()
        val params = EapiCrypto.encryptParams(fullUrl, data)

        val requestBody = FormBody.Builder().add("params", params).build()

        val request = Request.Builder()
            .url(fullUrl)
            .post(requestBody)
            .header("User-Agent", IOS_UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", cookieStr)
            .build()

        return plainClient.newCall(request).execute()
    }

    /**
     * weapi 加密 POST。用于受保护但走 weapi 的接口。
     * 参考官方 weapi：POST 路径为 `/weapi/<去掉 /api/ 前缀>`（并非 /api/…），payload 需
     * 注入 `csrf_token`（来自 Cookie 的 __csrf）。session Cookie 由上层传入。
     */
    fun weapiPost(path: String, payloadJson: String): okhttp3.Response {
        val rawPayload = try { JSONObject(payloadJson) } catch (_: Exception) { JSONObject() }
        getCsrfToken()?.let {
            if (it.isNotEmpty() && !rawPayload.has("csrf_token")) rawPayload.put("csrf_token", it)
        }
        val (params, encSecKey) = WeapiCrypto.encryptParams(rawPayload.toString())
        val weapiPath = if (path.startsWith("/api/")) "/weapi/" + path.removePrefix("/api/") else path
        val fullUrl = "https://music.163.com" + weapiPath
        val requestBody = FormBody.Builder()
            .add("params", params)
            .add("encSecKey", encSecKey)
            .build()
        val request = Request.Builder()
            .url(fullUrl)
            .post(requestBody)
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", currentCookie ?: "")
            .build()
        return plainClient.newCall(request).execute()
    }

    fun get(path: String, useInterface: Boolean = false): String {
        val host = if (useInterface) INTERFACE_URL else BASE_URL
        val request = Request.Builder()
            .url(host + path)
            .get()
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", currentCookie ?: "")
            .build()

        return plainClient.newCall(request).execute().body?.string() ?: throw Exception("empty response")
    }

    /**
     * 播放行为上报(webLog)。与官方 web 播放器一致,POST 到 clientlogusf 日志域,
     * body 为表单 `logs=<JSON 数组>`,并携带 session Cookie。
     *
     * 这条链路不走 eapi/weapi 加密,只做普通表单 POST + csrf_token。
     */
    fun postWeblog(weblogUrl: String, logsJson: String): okhttp3.Response {
        val request = Request.Builder()
            .url(weblogUrl)
            .post(FormBody.Builder().add("logs", logsJson).build())
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Cookie", currentCookie ?: "")
            .build()
        return plainClient.newCall(request).execute()
    }

    /** 从当前 Cookie 串中提取 __csrf token,用于 weblog 上报。 */
    fun getCsrfToken(): String? {
        val cookie = currentCookie ?: return null
        return cookie.split(';')
            .asSequence()
            .map { it.trim() }
            .filter { it.startsWith("__csrf=") }
            .map { it.removePrefix("__csrf=") }
            .firstOrNull()
    }

    private class CookieInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val originalRequest = chain.request()
            val cookie = currentCookie
            val newRequest = if (!cookie.isNullOrBlank()) {
                originalRequest.newBuilder()
                    .header("Cookie", cookie)
                    .header("User-Agent", UA)
                    .header("Referer", "https://music.163.com/")
                    .build()
            } else {
                originalRequest
            }
            return chain.proceed(newRequest)
        }
    }
}
