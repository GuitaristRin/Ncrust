package com.takahashirinta.ncrust.network

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.auth.QrLoginProtocol
import com.takahashirinta.ncrust.network.crypto.EapiCrypto
import com.takahashirinta.ncrust.network.crypto.WeapiCrypto
import com.takahashirinta.ncrust.network.crypto.XeapiCrypto
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.util.concurrent.TimeUnit

object RetrofitClient {
    private const val TAG = "RetrofitClient"
    private const val BASE_URL = "https://music.163.com"
    private const val INTERFACE_URL = "https://interface3.music.163.com"
    // eapi 客户端接口域：官方/参考实现全部 eapi 打到 interface.music.163.com（非 music.163.com）。
    // 读接口在 music 上宽松可通,但写操作(如 /eapi/radio/like)发错 host 会被判 -460 风险。
    private const val API_URL = "https://interface.music.163.com"
    // xeapi 业务域。官方 Android 9.x 的 /xeapi/... 固定打 interface3（spec/api/xeapi.md §3）。
    private const val XEAPI_URL = "https://interface3.music.163.com"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    private const val IOS_UA = "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1"
    // 扫码登录的 eapi 只认 PC 客户端身份：host 用 interfacepc，UA 用桌面客户端（协议见
    // spec/api/endpoints.md「eapi · PC 客户端指纹」）。
    private const val INTERFACE_PC_URL = "https://interfacepc.music.163.com"
    private const val PC_UA = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.29.205117"
    // 播放上报（听歌打卡）的日志域。旧通道 clientlogusf 回 200 但不入账，只有这里的
    // clientlog + eapi + os=osx 身份入账（依据 NeteaseCloudMusicApiEnhanced/api-enhanced#167，
    // 协议见 spec/api/endpoints.md 的「播放上报 / 听歌打卡」）。
    private const val CLIENT_LOG_URL = "https://clientlog.music.163.com"
    // os=osx 身份专用的 Mac Chrome UA：参考实现的 eapi 分支里 `cookie.os === 'osx'` 时才用它。
    private const val OSX_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    // 官方 Android 9.5.61 的 UA 与设备字段，xeapi 的公钥请求与业务请求共用同一套指纹。
    private const val ANDROID_UA = "NeteaseMusic/9.5.61.260802021928(9005061);Dalvik/2.1.0 (Linux; U; Android 12; HBN-AL00 Build/cd737a2.0)"
    private const val XEAPI_OS = "android"
    private const val XEAPI_OSVER = "16"
    private const val XEAPI_APPVER = "9.1.65"
    private const val ANTI_CHEAT_URL = "https://ac.dun.163yun.com/v3/b?pn=YD00000558929251"
    private val FORM_CONTENT_TYPE = "application/x-www-form-urlencoded;charset=utf-8".toMediaType()

    /** 易盾响应形如 `null([200,<n>,"<token>"])`（xeapi.md §5）。 */
    private val ANTI_CHEAT_RE = Regex("""null\(\[(\d+),\d+,"([^"]+)"\]\)""")

    private var currentCookie: String? = null

    fun init(context: Context) {
        currentCookie = CookieManager.getCookie(context)
        // deviceId / 公钥状态 / xeapi 会话；只读盘，不发网络（首次业务请求时才取公钥）。
        XeapiState.init(context)
    }

    fun updateCookie(cookie: String?) {
        currentCookie = cookie
        // 换登录态后旧的内存会话不再属于当前 cookie，清掉重取。
        XeapiState.clearSession()
    }

    fun getCookie(): String? = currentCookie

    private val plainClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 易盾 token 只是写入请求的加分项，不值得为它把一次收藏拖上 30 秒：单独一个短超时 client。
     * 取不到就发空串（上游同样处理），绝不阻断写入。
     */
    private val antiCheatClient: OkHttpClient by lazy {
        plainClient.newBuilder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .callTimeout(5, TimeUnit.SECONDS)
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
        useInterface: Boolean = false,
        // 显式指定本次请求的 cookie（扫码 803 后校验还没落盘的新会话）；null 表示用当前登录 cookie。
        cookieOverride: String? = null
    ): Response {
        val host = if (useInterface) INTERFACE_URL else API_URL
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
            .header("Cookie", cookieOverride ?: currentCookie ?: "")
            .build()

        return plainClient.newCall(request).execute()
    }

    /**
     * eapi + **PC 客户端身份** POST（打 interfacepc）。扫码登录专用：host、PC UA、设备字段
     * （按 `encodeURIComponent` 同时作 Cookie）与业务里的 `type=3` 缺一都拿不到 unikey——
     * 老通道 weapi + `type=1` 现在直接回 `-462`（协议与依据见 `spec/api/endpoints.md`）。
     *
     * 请求头只有 UA 与 Cookie（参考实现没带 Referer）；设备字段与信封的构造在
     * [QrLoginProtocol] 里，是纯函数、有单测。
     *
     * @param path `/api/…` 形态，内部改写成 `/eapi/…`。
     * @param fields 业务字段，值必须给原生类型（`type` 是数值）。
     */
    fun eapiPostPc(path: String, fields: Map<String, Any>): Response {
        val header = QrLoginProtocol.pcHeader(
            deviceId = deviceId(),
            nowMs = System.currentTimeMillis(),
            requestSuffix = (0..999).random()
        )
        return eapiPostWithIdentity(INTERFACE_PC_URL, PC_UA, path, fields, header)
    }

    /**
     * eapi + **os=osx 身份** POST（打 clientlog 日志域）。播放上报专用：听歌打卡只有
     * 「clientlog + eapi + os=osx」这个组合入账，android / iphone / pc 身份的同名请求会被丢弃
     * （依据 NeteaseCloudMusicApiEnhanced/api-enhanced#167，协议见 `spec/api/endpoints.md`）。
     *
     * 请求头只有 UA 与 Cookie（参考实现这条路径不带 Referer）；字段与顺序由 [osxHeader] 负责，
     * 是纯函数、有单测。
     *
     * @param path `/api/…` 形态，内部改写成 `/eapi/…`。
     * @param fields 业务字段（播放上报只有一个 `logs`，值是 JSON 文本）。
     */
    fun eapiPostOsx(path: String, fields: Map<String, Any>): Response {
        val cookie = cookieValues(currentCookie)
        val header = osxHeader(
            deviceId = deviceId(),
            csrf = getCsrfToken().orEmpty(),
            musicU = cookie["MUSIC_U"],
            musicA = cookie["MUSIC_A"],
            nowMs = System.currentTimeMillis(),
            requestSuffix = (0..999).random()
        )
        return eapiPostWithIdentity(CLIENT_LOG_URL, OSX_UA, path, fields, header)
    }

    /**
     * os=osx 身份的设备字段，**顺序即协议**（逐项对齐参考实现 util/request.js 的 eapi 分支）。
     *
     * 同一份既按 [QrLoginProtocol.cookieHeader] 作请求 Cookie，也整体进加密 body 的 `header`；
     * 服务端就是靠这组字段认出「桌面客户端」的 —— 换成 android / iphone / pc 身份，请求照回 200
     * 但不会入账。纯函数（deviceId 与时间由调用方给），JVM 单测直接断言字段与顺序。
     *
     * @param nowMs 毫秒时间戳：`buildver` 取它的前 10 位（等价于秒级时间戳），
     *   `requestId` 是 `<毫秒>_<4 位随机>`。
     */
    fun osxHeader(
        deviceId: String,
        csrf: String,
        musicU: String?,
        musicA: String?,
        nowMs: Long,
        requestSuffix: Int,
    ): LinkedHashMap<String, String> {
        val header = linkedMapOf(
            "osver" to "15.5",
            "deviceId" to deviceId,
            "os" to "osx",
            "appver" to "3.1.10.5100",
            "versioncode" to "140",
            "mobilename" to "",
            "buildver" to (nowMs / 1000).toString(),
            "resolution" to "1920x1080",
            "__csrf" to csrf,
            "channel" to "netease",
            "requestId" to "${nowMs}_${requestSuffix.toString().padStart(4, '0')}",
        )
        if (!musicU.isNullOrEmpty()) header["MUSIC_U"] = musicU
        if (!musicA.isNullOrEmpty()) header["MUSIC_A"] = musicA
        return header
    }

    /**
     * eapi + 客户端身份 POST 的公共拼装（PC 扫码 / osx 播报共用）：这两条通道除了 host 与 UA
     * （身份差异，作参数传入）之外形态完全一致 —— 设备字段按 [QrLoginProtocol.cookieHeader] 拼成
     * Cookie，同一份再进加密 body 的 `header`，信封是「业务字段 → `e_r:false` → `header`」。
     *
     * （第三条身份通道 [eapiPostOfficial] 是 iPhone 身份、拼法另有历史，不并入这里。）
     */
    private fun eapiPostWithIdentity(
        host: String,
        ua: String,
        path: String,
        fields: Map<String, Any>,
        header: Map<String, String>
    ): Response {
        val fullUrl = host + "/eapi/" + path.removePrefix("/api/")
        val params = EapiCrypto.encryptParams(fullUrl, QrLoginProtocol.envelope(fields, header))

        val request = Request.Builder()
            .url(fullUrl)
            .post(FormBody.Builder().add("params", params).build())
            .header("User-Agent", ua)
            .header("Cookie", QrLoginProtocol.cookieHeader(header))
            .build()

        return plainClient.newCall(request).execute()
    }

    /**
     * 客户端身份共用的设备 id：复用 xeapi 的持久设备 id（一台设备一个 id）。
     * 没初始化 [XeapiState] 时兜底随机，但**进程内只生成一次**——逐请求换 deviceId 正是
     * 风控画像里「非真实设备」的特征（`spec/api/xeapi.md` §3.3）。
     */
    private val fallbackDeviceId: String by lazy {
        buildString(32) { repeat(32) { append("0123456789abcdef"[(0..15).random()]) } }
    }

    fun deviceId(): String =
        runCatching { XeapiState.deviceId() }.getOrNull() ?: fallbackDeviceId

    /** 登录 Cookie 串 → 键值对（同名后者覆盖前者）；eapi 身份构造要按字段取值。 */
    private fun cookieValues(cookie: String?): Map<String, String> =
        (cookie ?: "")
            .split(';')
            .asSequence()
            .map { it.trim() }
            .filter { it.contains('=') }
            .map { it.split('=', limit = 2) }
            .associate { it[0].trim() to it.getOrElse(1) { "" } }

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
        val mus: Map<String, String> = cookieValues(currentCookie)
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
     * xeapi 公钥请求（明文表单，不走加密层）；解析、验签、落盘由 [XeapiState] 负责。
     * Cookie 只带 deviceId —— 这一步不涉及登录态，也不该把登录 cookie 掺进来。
     */
    internal fun xeapiPublicKeyRequest(formBody: String, deviceId: String): Response {
        // 公钥接口在 interface（API_URL），业务请求才在 interface3（spec/api/xeapi.md §2）。
        val request = Request.Builder()
            .url("$API_URL/api/bsr/sk/get")
            .post(formBody.toRequestBody(FORM_CONTENT_TYPE))
            .header("User-Agent", ANDROID_UA)
            .header("Cookie", "deviceId=$deviceId")
            .build()
        return plainClient.newCall(request).execute()
    }

    /**
     * xeapi 加密 POST —— 官方 Android 9.x 的请求层（协议全文 `spec/api/xeapi.md`）。
     *
     * 收藏写入走这里：老通道（weapi/eapi 的 `/api/radio/like`）写入的收藏会被服务端事后回滚
     * （「收藏隔天消失」，见 xeapi.md 末节）。
     *
     * 阻塞，调用方自己在 IO 线程调用。成功返回**已解密**的响应 JSON 文本；网络失败、公钥取不到
     * 或解密失败抛异常，由调用方决定是否回退到老通道。
     *
     * @param uri `/api/...` 形式的接口路径，实际打到 `interface3/xeapi/<去掉 /api/ 的路径>`。
     * @param pairs 业务字段，**顺序即协议**：明文信封的 `content` 按这个顺序做表单编码。
     * @param antiCheat 是否现取易盾 token（写入类接口需要；取不到发空串）。官方写入把同一份 token
     *   **同时**放进加密体的 `checkToken` 字段与 `X-antiCheatToken` 头（`xeapi.md` §6）——
     *   只放头、体里没有 `checkToken` 时，服务端把写入判为异常环境、事后回滚（「收藏隔天消失」）。
     */
    fun xeapiPost(uri: String, pairs: List<Pair<String, String>>, antiCheat: Boolean): String {
        val url = XEAPI_URL + "/xeapi/" + uri.removePrefix("/api/")
        // 易盾 token 每次写入现取、不缓存（xeapi.md §5）。
        val antiCheatToken = if (antiCheat) fetchAntiCheatToken() else null
        // 官方在业务体末尾补 `checkToken`（顺序：… trackId, like, time, checkToken）。
        val bodyPairs = if (antiCheatToken != null) pairs + ("checkToken" to antiCheatToken) else pairs

        val first = executeXeapi(url, bodyPairs, XeapiState.ensureKeyState(), antiCheatToken)
        decryptXeapiBody(first)?.let { return it }

        // 解不开通常意味着服务端换了公钥：按「旧 version」重取一次再试。只重试一次——
        // 写入接口的重放本身就是风控特征，不能在这里死磕。
        Log.w(TAG, "xeapi response decrypt failed, refreshing public key (uri=$uri)")
        XeapiState.invalidateKeyState()
        val second = executeXeapi(url, bodyPairs, XeapiState.ensureKeyState(), antiCheatToken)
        return decryptXeapiBody(second) ?: throw IOException("xeapi response decrypt failed: $uri")
    }

    private fun executeXeapi(
        url: String,
        pairs: List<Pair<String, String>>,
        keyState: XeapiCrypto.KeyState,
        antiCheatToken: String?
    ): ByteArray {
        val deviceId = XeapiState.deviceId()
        val buildver = (System.currentTimeMillis() / 1000).toString().take(10)
        val body = XeapiCrypto.encryptRequest(
            pairs = pairs,
            keyState = keyState,
            os = XEAPI_OS,
            session = XeapiState.session(),
            rng = XeapiState.newRng()
        ).body

        // 设备字段要「同名以后者为准」地压过登录 cookie 里的旧值：先收成 map 再覆盖，
        // 同一个键只出现一次，免得服务端读到登录时留下的旧 os/appver/deviceId。
        val cookiePairs = LinkedHashMap<String, String>()
        (currentCookie ?: "").split(';').forEach { part ->
            val sep = part.indexOf('=')
            if (sep > 0) cookiePairs[part.substring(0, sep).trim()] = part.substring(sep + 1).trim()
        }
        val musicU = cookiePairs["MUSIC_U"]
        cookiePairs["os"] = XEAPI_OS
        cookiePairs["osver"] = XEAPI_OSVER
        cookiePairs["appver"] = XEAPI_APPVER
        cookiePairs["buildver"] = buildver
        cookiePairs["deviceId"] = deviceId
        cookiePairs["sDeviceId"] = deviceId

        val builder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(FORM_CONTENT_TYPE))
            .header("User-Agent", ANDROID_UA)
            .header("X-Client-Enc-State", "ENCRYPTED")
            .header("x-aeapi", "true")
            .header("x-deviceid", deviceId)
            .header("x-sdeviceid", deviceId)
            .header("x-os", XEAPI_OS)
            .header("x-osver", XEAPI_OSVER)
            .header("x-appver", XEAPI_APPVER)
            .header("x-buildver", buildver)
            .header("Cookie", cookiePairs.entries.joinToString("; ") { "${it.key}=${it.value}" })
        // 已登录时 x-music-u 与 Cookie 里的 MUSIC_U 同值（xeapi.md §3.3）。
        if (!musicU.isNullOrEmpty()) builder.header("x-music-u", musicU)
        if (antiCheatToken != null) builder.header("X-antiCheatToken", antiCheatToken)

        return plainClient.newCall(builder.build()).execute().use { response ->
            // 响应头带 x-encr-ssid/x-encr-sskey 时记下，之后的请求走会话模式（只存内存）。
            XeapiState.updateSession(response.header("x-encr-ssid"), response.header("x-encr-sskey"))
            val raw = response.body?.bytes() ?: ByteArray(0)
            // 脱敏日志：只记路径、HTTP 码与长度，不记 cookie / 易盾 token / 加密体。
            Log.i(TAG, "xeapi $url http=${response.code} bytes=${raw.size}")
            raw
        }
    }

    /**
     * 解密 xeapi 响应体。解不开时若原文本身就是 JSON（服务端在参数错 / 风控时会直接回未加密
     * JSON），照原样返回让调用方拿得到业务码；否则返回 null，交给上层刷新公钥后重试。
     */
    private fun decryptXeapiBody(raw: ByteArray): String? {
        if (raw.isEmpty()) return null
        runCatching { XeapiCrypto.decryptResponse(raw) }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val plain = String(raw, Charsets.UTF_8).trim()
        return plain.takeIf { it.startsWith("{") || it.startsWith("[") }
    }

    /**
     * 易盾反作弊 token：`GET .../v3/b?pn=...` → `null([200,<n>,"<token>"])`，取第三项。
     * 取不到返回空串——缺 token 只是少一层客户端指纹，不该阻断写入。
     */
    private fun fetchAntiCheatToken(): String {
        return try {
            val request = Request.Builder().url(ANTI_CHEAT_URL).header("User-Agent", ANDROID_UA).build()
            antiCheatClient.newCall(request).execute().use { response ->
                val match = ANTI_CHEAT_RE.find(response.body?.string().orEmpty())
                when {
                    match == null -> {
                        Log.w(TAG, "antiCheat token unparseable (http=${response.code})")
                        ""
                    }
                    match.groupValues[1] != "200" -> {
                        Log.w(TAG, "antiCheat code=${match.groupValues[1]}")
                        ""
                    }
                    else -> match.groupValues[2]
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "antiCheat token request failed: ${e.message}")
            ""
        }
    }

    /**
     * weapi 加密 POST。用于受保护但走 weapi 的接口。
     * 参考官方 weapi：POST 路径为 `/weapi/<去掉 /api/ 前缀>`（并非 /api/…），payload 需
     * 注入 `csrf_token`（来自 Cookie 的 __csrf）。session Cookie 由上层传入。
     */
    fun weapiPost(
        path: String,
        payloadJson: String,
        extraCookies: String? = null
    ): okhttp3.Response {
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
        val builder = Request.Builder()
            .url(fullUrl)
            .post(requestBody)
            .header("User-Agent", UA)
            .header("Referer", "https://music.163.com/")
            .header("Origin", "https://music.163.com")
        // 未登录时不发空 Cookie 头, 与官方网页/参考实现一致；extraCookies 供需要额外
        // 会话 cookie 的接口追加字段（扫码登录已改走 eapiPostPc, 不再用这条通道）。
        val cookieHeader = listOfNotNull(
            currentCookie?.takeIf { it.isNotBlank() },
            extraCookies?.takeIf { it.isNotBlank() }
        ).joinToString("; ")
        if (cookieHeader.isNotEmpty()) builder.header("Cookie", cookieHeader)
        return plainClient.newCall(builder.build()).execute()
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

    /** 从当前 Cookie 串中提取 __csrf token,用于 weapi / eapi 身份头与播放上报。 */
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
