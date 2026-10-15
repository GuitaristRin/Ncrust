package com.takahashirinta.ncrust.auth

/**
 * 扫码登录的协议层：PC 客户端身份、加密信封与 Set-Cookie 解析。
 *
 * 2026-10 实测：weapi 的 `/api/login/qrcode/unikey`（`type=1` + `noCheckToken`）会被回 `-462`
 * （风控校验），二维码根本出不来。参考实现（NeteaseCloudMusicApiEnhanced/api-enhanced 的
 * `login_qr_key` / `login_qr_check`，本仓库的逐项对照脚本 `spec/tools/qr-login.mjs`）已改为
 * **eapi + PC 客户端身份 + `type=3`**，打 `interfacepc`。协议见 `spec/api/endpoints.md`。
 *
 * 这里全是纯函数（不碰 Android API），因为 header / payload 的形态与字符集是协议的一部分：
 * JVM 单测直接比对，不必上设备。HTTP 与加密分别由 `RetrofitClient` / `EapiCrypto` 负责。
 */
object QrLoginProtocol {
    /** 业务路径按 `/api/…` 形态书写，由 `RetrofitClient.eapiPostPc` 改写成 `/eapi/…`。 */
    const val UNIKEY_PATH = "/api/login/qrcode/unikey"
    const val CLIENT_LOGIN_PATH = "/api/login/qrcode/client/login"

    /** 参考实现里的 `type: 3`（PC 客户端二维码）。**必须是数值**，不能是字符串 `"3"`。 */
    const val UNIKEY_TYPE = 3

    private const val QR_CONTENT_PREFIX = "https://music.163.com/login?codekey="

    private const val PC_OSVER = "Microsoft-Windows-10-Professional-build-19045-64bit"
    private const val PC_OS = "pc"
    private const val PC_APPVER = "3.1.17.204416"
    private const val PC_VERSIONCODE = "140"
    private const val PC_RESOLUTION = "1920x1080"
    private const val PC_CHANNEL = "netease"

    /** encodeURIComponent 不转义的字符集。 */
    private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

    private const val HEX = "0123456789ABCDEF"

    /**
     * PC 客户端设备字段，**顺序即协议**（与参考实现逐项一致）。
     *
     * 同一份既按 [cookieHeader] 作请求 Cookie，也整体进加密 body 的 `header`；两处必须一致，
     * 服务端就是靠它把这次扫码绑到一个 PC 客户端会话上的。`deviceId` 复用 xeapi 的持久设备 id
     * （一台设备一个 id，见 `XeapiState`）。
     *
     * @param nowMs 毫秒时间戳。`buildver` 取它的前 10 位（等价于秒级时间戳），
     *   `requestId` 是 `<毫秒>_<4 位随机>`。
     */
    fun pcHeader(deviceId: String, nowMs: Long, requestSuffix: Int): LinkedHashMap<String, String> =
        linkedMapOf(
            "osver" to PC_OSVER,
            "deviceId" to deviceId,
            "os" to PC_OS,
            "appver" to PC_APPVER,
            "versioncode" to PC_VERSIONCODE,
            "mobilename" to "",
            "buildver" to (nowMs / 1000).toString(),
            "resolution" to PC_RESOLUTION,
            // 扫码时还没登录，参考实现固定发空串。
            "__csrf" to "",
            "channel" to PC_CHANNEL,
            "requestId" to "${nowMs}_${requestSuffix.toString().padStart(4, '0')}",
        )

    /** header 同时用作 Cookie：`encodeURIComponent(k)=encodeURIComponent(v)`，以 `; ` 拼接。 */
    fun cookieHeader(header: Map<String, String>): String =
        header.entries.joinToString("; ") { (name, value) -> "${encodeComponent(name)}=${encodeComponent(value)}" }

    /**
     * 加密信封：业务字段 → `e_r:false` → `header`，字段顺序与参考实现一致。
     *
     * 值按原生 JSON 类型给（`type` 是数值、`e_r` 是布尔）：序列化由 `EapiCrypto` 用 `JSONObject`
     * 完成，它保留插入顺序，也保留数值 / 布尔的原生形态。收成字符串就会把 `"type":3` 变成
     * `"type":"3"`，那是另一个请求。
     */
    fun envelope(fields: Map<String, Any>, header: Map<String, String>): LinkedHashMap<String, Any> {
        val payload = LinkedHashMap<String, Any>(fields)
        payload["e_r"] = false
        payload["header"] = header
        return payload
    }

    /** 申请 unikey 的业务字段。 */
    fun unikeyFields(): Map<String, Any> = linkedMapOf("type" to UNIKEY_TYPE)

    /** 轮询的业务字段。 */
    fun clientLoginFields(unikey: String): Map<String, Any> = linkedMapOf("key" to unikey, "type" to UNIKEY_TYPE)

    /**
     * 二维码内容：PC 形态，**不带** `chainId` —— 那是 web 登录会话的绑定字段，PC 身份不用它。
     * 局域网交接（`QrPair`）只从 `codekey` 取 unikey，因此这份内容照旧可用。
     */
    fun qrContent(unikey: String): String = QR_CONTENT_PREFIX + unikey

    /**
     * Set-Cookie 头 → 会话 cookie 串（对应 Windows `QrLoginClient.ExtractSessionCookie`）。
     *
     * 同一响应里可能有两条同名 `MUSIC_U`（删除用的空值与真值，顺序不定）：解析时**忽略空值**，
     * 于是最后一个非空值生效，顺带去重；没有非空 `MUSIC_U` 就不算登录成功（803 也可能只给
     * 删除写法，旧实现按子串匹配会把空值当成功）。
     */
    fun sessionCookie(setCookies: List<String>): String? {
        val jar = LinkedHashMap<String, String>()
        for (line in setCookies) {
            val pair = line.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val value = pair.substring(eq + 1)
            if (value.isEmpty()) continue
            jar[pair.substring(0, eq).trim()] = value
        }
        if (jar["MUSIC_U"].isNullOrEmpty()) return null
        return jar.entries.joinToString("; ") { (name, value) -> "$name=$value" }
    }

    /**
     * `encodeURIComponent` 的等价实现（保留 `A-Za-z0-9-_.!~*'()`，其余按 UTF-8 逐字节 `%XX` 大写）。
     * 不用 `URLEncoder`：它把空格编成 `+`、且不转义 `*`，与参考实现的 Cookie 形态不同。
     */
    fun encodeComponent(value: String): String {
        val sb = StringBuilder(value.length)
        for (byte in value.toByteArray(Charsets.UTF_8)) {
            val code = byte.toInt() and 0xFF
            if (code < 0x80 && UNRESERVED.indexOf(code.toChar()) >= 0) {
                sb.append(code.toChar())
            } else {
                sb.append('%').append(HEX[code shr 4]).append(HEX[code and 0x0F])
            }
        }
        return sb.toString()
    }
}
