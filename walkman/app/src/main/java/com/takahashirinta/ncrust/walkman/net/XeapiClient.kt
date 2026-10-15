package com.takahashirinta.ncrust.walkman.net

import com.takahashirinta.ncrust.walkman.crypto.Xeapi
import com.takahashirinta.ncrust.walkman.json.Json
import java.security.SecureRandom

/**
 * xeapi 业务请求（官方 Android 9.x 的通道，`spec/api/xeapi.md`）。
 *
 * 流程：取公钥（明文表单 + 验签 + AES-256-ECB 解）→ 用公钥加密业务请求 → 响应 AES-128-ECB 解。
 *
 * 状态（deviceId / 公钥 / 会话）都挂在单例上，因为协议要求它们**跨请求保持**：
 * `deviceId` 每台设备一个（换 id 正是风控画像里"非真实设备"的特征），公钥按 `nextUpdateTime`
 * 刷新，`x-encr-ssid`/`x-encr-sskey` 会话只存内存。
 *
 * **W0 的边界**：`deviceId` 只活在进程内，进程重启就换一个 —— 持久化是 W1 的事
 * （要落到 SharedPreferences 并保证登出也不清）。这里显式记着，免得 W1 忘掉。
 */
object XeapiClient {

    private const val APP_VERSION = "9.5.61"
    private const val OS_VER = "16"
    private const val X_APP_VER = "9.1.65"

    private val random = SecureRandom()

    /** 每台设备一个（32 位小写 hex）。W1 改成持久化。 */
    @Volatile
    var deviceId: String = randomDeviceId()
        private set

    @Volatile
    private var keyState: Xeapi.KeyState? = null

    @Volatile
    private var nextUpdateTime: Long = 0

    /** 上一次响应头带回来的会话（只存内存，进程级）。 */
    @Volatile
    private var session: Xeapi.Session? = null

    /** 业务请求。`apiPath` 形如 `/api/v1/radio/like`。 */
    fun post(apiPath: String, pairs: List<Pair<String, String>>): Requests.Raw {
        val state = keyState()
        val request = Xeapi.encryptRequest(
            pairs = pairs,
            keyState = state,
            os = "android",
            session = session,
            rng = Xeapi.XeapiRng.random()
        )
        val url = Requests.INTERFACE_URL + "/xeapi/" + apiPath.removePrefix("/api/")
        val response = Requests.postRawForm(url, request.body, businessHeaders(), Http.ANDROID_UA)
        response.session?.let { session = it }
        return response
    }

    /** 解出业务 JSON 文本；解不开返回 null（而不是抛异常，让调用方决定怎么报）。 */
    fun decrypt(response: Requests.Raw): String? = runCatching {
        Xeapi.decryptResponse(response.bytes)
    }.getOrNull()

    /** 取公钥状态；缓存有效则直接用。`force` 用于"业务请求失败后刷新一次"。 */
    @Synchronized
    fun keyState(force: Boolean = false): Xeapi.KeyState {
        val cached = keyState
        if (!force && cached != null && System.currentTimeMillis() < nextUpdateTime) return cached
        val fresh = fetchKeyState(cached?.version.orEmpty())
        keyState = fresh
        return fresh
    }

    /**
     * `POST /api/bsr/sk/get`（明文表单，打 API_URL）。
     *
     * 三处必须照做，少一处就是静默失败：
     *  1. `signature = sign(timestamp, nonce)`，`nonce` 是**请求时**那 16 位十进制数；
     *  2. 响应必须**验签**：`data.signature == sign(data.timestamp, 同一个 nonce)`。
     *     不验签等于接受任何人的公钥，`S` 就加给攻击者了；
     *  3. 解密结果缺 `sk` 视为失败 —— 缺 `sk` 时 `S` 的明文少一段，服务端一定拒。
     */
    private fun fetchKeyState(currentKeyVersion: String): Xeapi.KeyState {
        val timestamp = System.currentTimeMillis().toString()
        val nonce = randomNonce()
        val signature = Xeapi.sign(timestamp, nonce)

        val form = listOf(
            "appVersion" to APP_VERSION,
            "currentKeyVersion" to currentKeyVersion,
            "deviceId" to deviceId,
            "nonce" to nonce,
            "os" to "android",
            "requestType" to "active",
            "signature" to signature,
            "t1" to "",
            "t2" to "",
            "timestamp" to timestamp,
            "uid" to ""
        )
        val response = Requests.postForm(
            url = Requests.API_URL + "/api/bsr/sk/get",
            form = form,
            extraHeaders = mapOf("Cookie" to "deviceId=$deviceId"),
            userAgent = Http.ANDROID_UA
        )
        check(response.isHttpOk) { "公钥接口 HTTP ${response.httpCode}" }

        val root = Json.parseOrNull(response.text) ?: error("公钥接口响应不是 JSON")
        check(root.intOrNull("code") == 200) { "公钥接口 code=${root.intOrNull("code")}" }
        val data = root.objOrNull("data") ?: error("公钥接口响应缺 data")

        val responseTimestamp = data.field("timestamp")?.let { node ->
            (node as? Json.Num)?.raw ?: (node as? Json.Str)?.value
        } ?: error("公钥接口响应缺 timestamp")
        check(data.strOrNull("signature") == Xeapi.sign(responseTimestamp, nonce)) {
            "公钥接口响应验签不通过"
        }

        val encrypted = data.strOrNull("encryptedData") ?: error("公钥接口响应缺 encryptedData")
        val plain = Json.parse(Xeapi.decryptPublicKey(encrypted))
        val publicKey = plain.strOrNull("publicKey") ?: error("公钥状态缺 publicKey")
        val sk = plain.strOrNull("sk") ?: error("公钥状态缺 sk")
        val version = plain.strOrNull("version") ?: error("公钥状态缺 version")
        nextUpdateTime = plain.longOrNull("nextUpdateTime") ?: 0L
        return Xeapi.KeyState(publicKey = publicKey, version = version, sk = sk)
    }

    /**
     * 业务请求头与 Cookie（`spec/api/xeapi.md` §3.3）。
     *
     * `buildver` 是**10 位秒级时间戳**，且请求头与 Cookie 里必须是同一个值 —— 两边不一致
     * 是很容易漏的一处（协议明确写了"同上"）。
     */
    private fun businessHeaders(): Map<String, String> {
        val buildVer = (System.currentTimeMillis() / 1000).toString()
        val cookie = buildString {
            Http.cookie?.takeIf { it.isNotEmpty() }?.let { append(it).append("; ") }
            append("os=android; osver=").append(OS_VER)
            append("; appver=").append(X_APP_VER)
            append("; buildver=").append(buildVer)
            append("; deviceId=").append(deviceId)
            append("; sDeviceId=").append(deviceId)
        }
        val headers = mutableMapOf(
            "Content-Type" to "application/x-www-form-urlencoded;charset=utf-8",
            "X-Client-Enc-State" to "ENCRYPTED",
            "x-aeapi" to "true",
            "x-deviceid" to deviceId,
            "x-sdeviceid" to deviceId,
            "x-os" to "android",
            "x-osver" to OS_VER,
            "x-appver" to X_APP_VER,
            "x-buildver" to buildVer,
            "Cookie" to cookie
        )
        Http.cookieValue("MUSIC_U")?.let { headers["x-music-u"] = it }
        return headers
    }

    /** 16 位十进制（允许前导 0，所以不能走 `nextLong` 再取模）。 */
    private fun randomNonce(): String {
        val sb = StringBuilder(16)
        repeat(16) { sb.append(random.nextInt(10)) }
        return sb.toString()
    }

    private fun randomDeviceId(): String {
        val bytes = ByteArray(16)
        random.nextBytes(bytes)
        val sb = StringBuilder(32)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append("0123456789abcdef"[v ushr 4]).append("0123456789abcdef"[v and 0x0f])
        }
        return sb.toString()
    }
}
