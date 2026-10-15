package com.takahashirinta.ncrust.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.takahashirinta.ncrust.network.crypto.XeapiCrypto
import org.json.JSONObject
import java.io.IOException
import java.security.SecureRandom

/**
 * xeapi 的设备身份、公钥状态与会话（协议：`spec/api/xeapi.md` §2–§3）。
 *
 * 三类状态的生命周期刻意不同：
 *  - **deviceId**：每台设备只生成一次（32 位小写 hex），落 `ncrust_prefs`，登出也不清。
 *    每次请求换 deviceId 正是风控画像里「非真实设备」的特征（xeapi.md §3.3），
 *    而「收藏隔天消失」要修的恰恰是这个画像问题。
 *  - **公钥状态**：落盘（同 `ncrust_prefs`），按 `nextUpdateTime` 过期或用旧 version 主动刷新。
 *  - **会话**（`x-encr-ssid`/`x-encr-sskey`）：只存内存。它绑定服务端一次性的加密会话，
 *    落盘后在进程重启时可能已失效，徒增一次必然失败的请求。
 *
 * 刷新全程在 [keyLock] 内，并发调用只会真正发一次公钥请求——多个页面同时点赞时很容易并发进来。
 */
object XeapiState {
    private const val TAG = "XeapiState"

    // 与 CookieManager 同一个 prefs 文件：deviceId 属于「这台设备」而不是某次登录会话。
    private const val PREFS_NAME = "ncrust_prefs"
    private const val KEY_DEVICE_ID = "xeapi_device_id"
    private const val KEY_PUBLIC_KEY = "xeapi_public_key"
    private const val KEY_VERSION = "xeapi_key_version"
    private const val KEY_SK = "xeapi_sk"
    private const val KEY_NEXT_UPDATE = "xeapi_next_update_time"

    /** 公钥接口的 `appVersion`（官方 9.5.61，xeapi.md §2）。 */
    private const val KEY_APP_VERSION = "9.5.61"

    /** 响应没给 `nextUpdateTime`（或已过期）时的兜底有效期，避免每次请求都打公钥接口。 */
    private const val FALLBACK_KEY_TTL_MS = 12 * 60 * 60 * 1000L

    private val random = SecureRandom()

    @Volatile private var appContext: Context? = null
    @Volatile private var cachedDeviceId: String? = null
    @Volatile private var cachedKeyState: XeapiCrypto.KeyState? = null

    /** 业务请求解密失败时置位：下一次 [ensureKeyState] 强制带旧 version 刷新。 */
    @Volatile private var keyStale = false

    @Volatile private var cachedSession: XeapiCrypto.Session? = null

    private val keyLock = Any()

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 由 `RetrofitClient.init(context)` 调用（主线程，只读盘 + 可能生成一次 deviceId，不发网络）。 */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        val prefs = prefs(app)
        val stored = prefs.getString(KEY_DEVICE_ID, null)
        cachedDeviceId = if (stored != null && stored.length == 32) stored else generateDeviceId(prefs)
        cachedKeyState = loadKeyState(prefs)
        Log.i(TAG, "xeapi init deviceId=${deviceId().take(8)}… keyCached=${cachedKeyState != null}")
    }

    /** 业务请求与公钥请求共用同一个 deviceId；未初始化就调用属于编程错误，直接暴露。 */
    fun deviceId(): String = cachedDeviceId ?: throw IllegalStateException("XeapiState.init(context) 未调用")

    // ==================== 会话 ====================

    fun session(): XeapiCrypto.Session? = cachedSession

    /**
     * 记下响应头里的会话。两个头缺一不可；只有其一（或都缺）时保留现有会话——
     * 参考实现同样处理（`spec/tools/ncm-client.mjs`），空值不该把有效会话抹掉。
     */
    fun updateSession(id: String?, key: String?) {
        if (id.isNullOrEmpty() || key.isNullOrEmpty()) {
            Log.w(TAG, "xeapi session headers incomplete (id=${!id.isNullOrEmpty()} key=${!key.isNullOrEmpty()}), keep current")
            return
        }
        cachedSession = XeapiCrypto.Session(id, key)
    }

    /** 登录态变化时清掉内存会话（换账号后旧会话不再属于当前 cookie）。 */
    fun clearSession() {
        cachedSession = null
    }

    // ==================== 公钥状态 ====================

    /** 生产随机量（每次业务请求一份新的动态密钥 / mid 随机数 / 临时私钥 / IV）。 */
    fun newRng(): XeapiCrypto.Rng = XeapiCrypto.randomRng(random)

    /** 业务请求解密失败后调用，让下一次 [ensureKeyState] 走刷新（带旧 version）。 */
    fun invalidateKeyState() {
        keyStale = true
    }

    /**
     * 返回可用的公钥状态：缓存未过期直接用，缺失/过期/被标记失效时刷新。
     * 阻塞（内部是同步 HTTP），调用方必须在 IO 线程。
     *
     * @throws IOException 公钥接口失败或响应验签/字段校验不过。
     */
    fun ensureKeyState(): XeapiCrypto.KeyState = synchronized(keyLock) {
        val cached = cachedKeyState
        if (!keyStale && cached != null && System.currentTimeMillis() < cached.nextUpdateTime) {
            return cached
        }
        refreshLocked()
    }

    private fun refreshLocked(): XeapiCrypto.KeyState {
        val context = appContext ?: throw IllegalStateException("XeapiState.init(context) 未调用")
        val currentDeviceId = deviceId()
        // nonce 是 16 位十进制；响应验签要用同一个 nonce（见下）。
        val nonce = buildString(16) { repeat(16) { append(random.nextInt(10)) } }
        val timestamp = System.currentTimeMillis().toString()
        val body = XeapiCrypto.formEncode(
            listOf(
                "appVersion" to KEY_APP_VERSION,
                // 带旧 version：服务端据此决定是否换发新公钥。
                "currentKeyVersion" to (cachedKeyState?.version ?: ""),
                "deviceId" to currentDeviceId,
                "nonce" to nonce,
                "os" to "android",
                "requestType" to "active",
                "signature" to XeapiCrypto.sign(timestamp, nonce),
                "t1" to "",
                "t2" to "",
                "timestamp" to timestamp,
                "uid" to ""
            )
        )

        val response = RetrofitClient.xeapiPublicKeyRequest(body, currentDeviceId)
        val text = response.body?.string() ?: throw IOException("xeapi public key: empty response")
        if (!response.isSuccessful) {
            throw IOException("xeapi public key: http=${response.code} body=${text.take(120)}")
        }
        val json = try {
            JSONObject(text)
        } catch (e: Exception) {
            throw IOException("xeapi public key: bad json (${e.message})")
        }
        val code = json.optInt("code", -1)
        if (code != 200) throw IOException("xeapi public key: code=$code")
        val data = json.optJSONObject("data") ?: throw IOException("xeapi public key: no data")
        // 必须验签：签名对不上说明响应不是给这次 nonce 的，换了公钥等于把动态密钥交给别人。
        if (data.optString("signature") != XeapiCrypto.sign(data.optString("timestamp"), nonce)) {
            throw IOException("xeapi public key: response signature mismatch")
        }
        val stateJson = try {
            JSONObject(XeapiCrypto.decryptPublicKey(data.optString("encryptedData")))
        } catch (e: Exception) {
            throw IOException("xeapi public key: encryptedData decrypt failed (${e.message})")
        }
        val publicKey = stateJson.optString("publicKey")
        val version = stateJson.optString("version")
        val sk = stateJson.optString("sk")
        // 缺 sk 视为失败：S 的信封明文要带 sk，服务端靠它认设备身份。
        if (publicKey.isEmpty() || sk.isEmpty()) {
            throw IOException("xeapi public key: missing publicKey/sk in payload")
        }
        val now = System.currentTimeMillis()
        val nextUpdateTime = stateJson.optLong("nextUpdateTime", 0L).takeIf { it > now }
            ?: (now + FALLBACK_KEY_TTL_MS)
        val state = XeapiCrypto.KeyState(publicKey, version, sk, nextUpdateTime)
        cachedKeyState = state
        keyStale = false
        runCatching {
            prefs(context).edit()
                .putString(KEY_PUBLIC_KEY, state.publicKey)
                .putString(KEY_VERSION, state.version)
                .putString(KEY_SK, state.sk)
                .putLong(KEY_NEXT_UPDATE, state.nextUpdateTime)
                .apply()
        }.onFailure { Log.w(TAG, "persist key state failed: ${it.message}") }
        Log.i(TAG, "xeapi public key refreshed version=$version nextUpdateTime=$nextUpdateTime")
        return state
    }

    private fun loadKeyState(prefs: SharedPreferences): XeapiCrypto.KeyState? {
        val publicKey = prefs.getString(KEY_PUBLIC_KEY, null) ?: return null
        val sk = prefs.getString(KEY_SK, null) ?: return null
        if (publicKey.isEmpty() || sk.isEmpty()) return null
        return XeapiCrypto.KeyState(
            publicKey = publicKey,
            version = prefs.getString(KEY_VERSION, null).orEmpty(),
            sk = sk,
            nextUpdateTime = prefs.getLong(KEY_NEXT_UPDATE, 0L)
        )
    }

    private fun generateDeviceId(prefs: SharedPreferences): String {
        val bytes = ByteArray(16).also(random::nextBytes)
        val id = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_DEVICE_ID, id).apply()
        Log.i(TAG, "xeapi deviceId generated (first run on this device)")
        return id
    }
}
