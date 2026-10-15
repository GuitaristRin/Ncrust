package com.takahashirinta.ncrust.walkman.net

import com.takahashirinta.ncrust.walkman.crypto.Eapi
import com.takahashirinta.ncrust.walkman.crypto.Weapi
import com.takahashirinta.ncrust.walkman.crypto.Xeapi
import com.takahashirinta.ncrust.walkman.json.Json
import okhttp3.MediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.Response
import java.security.SecureRandom

/**
 * 三种请求风格的传输层：eapi / weapi / xeapi。
 *
 * 只负责"把密文拼好、发出去、把明文拿回来"，不管业务字段 —— 业务在 `api/` 里。
 * 三者共用 [Http.client] 与 [Http.cookie]，所以连接池、TLS 配置、登录态只有一份。
 *
 * 返回值里的 [Raw.tlsVersion] 不是调试残留：本工程的第一个风险就是"4.1 上到底协商到了
 * 哪个 TLS 版本"，冒烟界面要把它显示出来（见 `docs/f885-spike-report.md` §2）。
 */
object Requests {

    const val BASE_URL = "https://music.163.com"
    const val API_URL = "https://interface.music.163.com"

    /** 取链与 xeapi 业务请求都打这里（`spec/api/endpoints.md` 的主机表）。 */
    const val INTERFACE_URL = "https://interface3.music.163.com"

    private val FORM_UTF8: MediaType? =
        MediaType.parse("application/x-www-form-urlencoded;charset=utf-8")

    private val random = SecureRandom()

    /** 原始响应。xeapi 的响应是 AES 密文，**必须先拿字节**，所以这条是所有请求的公共出口。 */
    class Raw(
        val httpCode: Int,
        val bytes: ByteArray,
        val tlsVersion: String,
        /** 响应头带 `x-encr-ssid` / `x-encr-sskey` 时的会话（只有 xeapi 会用到）。 */
        val session: Xeapi.Session?
    ) {
        val isHttpOk: Boolean get() = httpCode in 200..299

        /** 响应体按 UTF-8 文本读；eapi / weapi / 明文接口都用它。 */
        val text: String get() = String(bytes, Charsets.UTF_8)
    }

    /** eapi：表单 `params=<hex>`。`useInterface = true` 时打 interface3（取链走这条）。 */
    fun eapiPost(path: String, payloadJson: String, useInterface: Boolean = false): Raw {
        val params = Eapi.encryptParams(path, payloadJson)
        val host = if (useInterface) INTERFACE_URL else API_URL
        return postForm(
            url = host + path,
            form = listOf("params" to params),
            referer = "https://music.163.com/"
        )
    }

    /**
     * weapi：表单 `params` + `encSecKey`，打 `music.163.com`。
     *
     * secKey 每次请求重新随机（协议要求），随机源用 [SecureRandom] —— weapi 的 `encSecKey`
     * 就是这个 secKey 的 RSA，可预测的 secKey 等于把两层 AES 的第一层白送。
     */
    fun weapiPost(path: String, payloadJson: String): Raw {
        val (params, encSecKey) = Weapi.encryptParams(
            weapiPayload(payloadJson, Http.cookieValue("__csrf")), Weapi.randomSecKey(random)
        )
        return postForm(
            url = BASE_URL + path.replace("/api/", "/weapi/"),
            form = listOf("params" to params, "encSecKey" to encSecKey),
            referer = "https://music.163.com/"
        )
    }

    internal fun weapiPayload(payloadJson: String, csrf: String?): String {
        val obj = Json.parse(payloadJson) as? Json.Obj ?: error("weapi payload must be an object")
        if (obj.fields.containsKey("csrf_token")) return payloadJson
        val body = payloadJson.trim().dropLast(1)
        val separator = if (obj.fields.isEmpty()) "" else ","
        return body + separator + "\"csrf_token\":" + Json.quote(csrf.orEmpty()) + "}"
    }

    /**
     * 明文表单 POST（xeapi 的公钥接口走这条）。
     *
     * 表单体用本工程的 [Form] 拼，**不用 OkHttp 的 `FormBody`**：后者的转义规则与 WHATWG 不同
     * （`~` 与 `*` 上会分叉），而 xeapi 夹具逐字节盯着这个。
     */
    fun postForm(
        url: String,
        form: List<Pair<String, String>>,
        referer: String? = null,
        extraHeaders: Map<String, String> = emptyMap(),
        userAgent: String = Http.PC_UA
    ): Raw = execute(
        Request.Builder()
            .url(url)
            .post(RequestBody.create(FORM_UTF8, Form.encode(form)))
            .applyHeaders(userAgent, referer, extraHeaders)
            .build()
    )

    /** 已拼好的表单体 POST（xeapi 业务请求用，`C=…&S=…&R=…` 由加密层给出）。 */
    fun postRawForm(
        url: String,
        body: String,
        headers: Map<String, String>,
        userAgent: String
    ): Raw = execute(
        Request.Builder()
            .url(url)
            .post(RequestBody.create(FORM_UTF8, body))
            .applyHeaders(userAgent, null, headers)
            .build()
    )

    /** 明文 GET。 */
    fun get(url: String, referer: String? = "https://music.163.com/"): Raw =
        execute(Request.Builder().url(url).get().applyHeaders(Http.PC_UA, referer, emptyMap()).build())

    private fun Request.Builder.applyHeaders(
        userAgent: String,
        referer: String?,
        extra: Map<String, String>
    ): Request.Builder {
        header("User-Agent", userAgent)
        if (referer != null) header("Referer", referer)
        // cookie 由上层注入（W1 做持久化）。匿名时为空，此时不带这个头。
        Http.cookie?.takeIf { it.isNotEmpty() }?.let { header("Cookie", it) }
        for ((k, v) in extra) header(k, v)
        return this
    }

    private fun execute(request: Request): Raw =
        Http.client.newCall(request).execute().use { response ->
            Raw(
                httpCode = response.code(),
                bytes = response.body()?.bytes() ?: ByteArray(0),
                tlsVersion = Http.tlsVersionOf(response),
                session = sessionOf(response)
            )
        }

    private fun sessionOf(response: Response): Xeapi.Session? {
        val id = response.header("x-encr-ssid") ?: return null
        val key = response.header("x-encr-sskey") ?: return null
        return Xeapi.Session(id, key)
    }
}
