package com.takahashirinta.ncrust.network

import android.util.Log
import com.takahashirinta.ncrust.account.SessionMonitor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * 歌单写操作结果。**HTTP 状态码恒为 200，成败一律看 `body.code`** —— 服务端把业务错误
 * 放在 body 里（405 限流、403 非法请求、301 未登录…），只看 HTTP 会全部当成成功。
 */
sealed interface PlaylistWriteResult {
    /** 服务端业务码；网络层异常无业务码（null）。 */
    val businessCode: Int?

    data class Success(val raw: JSONObject) : PlaylistWriteResult {
        override val businessCode: Int get() = 200
    }

    /** 502「歌单(内)歌曲重复」= 这首歌已经在歌单里，是幂等成功而非失败。 */
    data object Duplicate : PlaylistWriteResult {
        override val businessCode: Int get() = 502
    }

    /**
     * 405 操作过于频繁。实测是**账号+动作级限流且按身份维度计算** —— 同一时刻某身份持续
     * 405，换成新 `deviceId`/`os` 立刻通过。手动场景直接提示等待；**绝不自动重试**
     * （原地重试只会再吃一次 405）。
     */
    data object RateLimited : PlaylistWriteResult {
        override val businessCode: Int get() = 405
    }

    /** 403 illegal request!：身份 cookie / `csrf_token` 没带全，属客户端实现错误。 */
    data object IllegalRequest : PlaylistWriteResult {
        override val businessCode: Int get() = 403
    }

    data class Failure(val code: Int, val message: String, val httpCode: Int = 200) : PlaylistWriteResult {
        override val businessCode: Int get() = code
    }

    data class NetworkError(val error: IOException) : PlaylistWriteResult {
        override val businessCode: Int? get() = null
    }
}

/** 写操作闸门：**全局串行 + 最小间隔 2s**，避免连点触发服务端 405「操作过于频繁」。 */
object PlaylistWriteGate {
    /** 实测：create 连发会触发 405，串行且间隔 ≥1.5s 稳定通过；取整为 2s 留余量。 */
    private const val MIN_INTERVAL_MS = 2_000L

    private val mutex = Mutex()
    private var lastWriteAt = 0L

    suspend fun <T> run(block: suspend () -> T): T = mutex.withLock {
        val wait = MIN_INTERVAL_MS - (System.currentTimeMillis() - lastWriteAt)
        if (wait > 0) delay(wait)
        try {
            block()
        } finally {
            lastWriteAt = System.currentTimeMillis()
        }
    }
}

/**
 * 歌单写操作（创建 / 删除 / 改名 / 改简介 / 改隐私 / 增删曲）—— 协议见 `spec/api/endpoints.md`
 * 的「歌单写操作」节。
 *
 * 全部走 eapi（`/eapi/playlist/...`，打 `API_URL`），且必须带**客户端身份 cookie**
 * （[ClientIdentity.withIdentity]）：只带登录 cookie 会回 `403 illegal request!`。
 */
object PlaylistEditApi {
    private const val TAG = "PlaylistEditApi"
    private const val CREATE_PATH = "/eapi/playlist/create"
    private const val TRACKS_PATH = "/eapi/playlist/manipulate/tracks"
    private const val DELETE_PATH = "/eapi/playlist/delete"
    private const val REMOVE_PATH = "/eapi/playlist/remove"
    private const val UPDATE_NAME_PATH = "/eapi/playlist/update/name"
    private const val UPDATE_DESC_PATH = "/eapi/playlist/desc/update"
    private const val UPDATE_PRIVACY_PATH = "/eapi/playlist/update/privacy"

    /** 隐私取值只有这两个；传别的值会被 400「错误的歌单隐私类型」拒绝。 */
    const val PRIVACY_PUBLIC = 0
    const val PRIVACY_PRIVATE = 10

    /** 单请求曲目上限；超过就分批（`>1000` 首的歌单判重不可靠，见 [addSongs]）。 */
    private const val TRACK_BATCH = 500

    // ==================== 端点 ====================

    /**
     * 创建歌单。返回新 pid；失败返回 null（具体原因看 [lastError]）。
     *
     * 响应里顶层 `id` 与 `playlist.id` 同时存在且相等，只认其中一个都行。
     */
    suspend fun createPlaylist(name: String, privacy: Int = PRIVACY_PUBLIC): Long? {
        val payload = mapOf(
            "name" to name,
            "privacy" to privacy.toString(),
            "type" to "NORMAL",
            "subType" to "NORMAL",
        )
        return when (val result = write(CREATE_PATH, payload)) {
            is PlaylistWriteResult.Success -> {
                val id = result.raw.optLong("id").takeIf { it > 0 }
                    ?: result.raw.optJSONObject("playlist")?.optLong("id")?.takeIf { it > 0 }
                if (id == null) Log.w(TAG, "create succeeded but no pid in response")
                id
            }
            else -> {
                recordError("createPlaylist", result)
                null
            }
        }
    }

    /**
     * 向歌单添加歌曲。
     *
     * **判重警告**：详情端点 `n` 上限 1000 且 `s`/`offset` 无效，服务端没有可翻页的曲目
     * 端点，因此 `>1000` 首的歌单无法可靠判重 —— 这种歌单由调用方直接 add，命中 502
     * （歌曲重复）当幂等成功处理，UI 统一提示「已在歌单中」。
     *
     * @return 成功时返回操作后的曲目总数（服务端 `count` 字段），失败 null
     */
    suspend fun addSongs(playlistId: Long, songIds: List<Long>): Int? {
        if (songIds.isEmpty()) return null
        var count: Int? = null
        for (batch in songIds.distinct().chunked(TRACK_BATCH)) {
            when (val result = write(TRACKS_PATH, addSongsPayload(playlistId, batch))) {
                is PlaylistWriteResult.Success -> count = result.raw.optInt("count", count ?: 0)
                PlaylistWriteResult.Duplicate -> count = count ?: 0
                else -> {
                    recordError("addSongs", result)
                    return null
                }
            }
        }
        return count
    }

    /** 从歌单移除歌曲（`op=del`）。返回操作后的曲目总数，失败 null。 */
    suspend fun removeSongs(playlistId: Long, songIds: List<Long>): Int? {
        if (songIds.isEmpty()) return null
        var count: Int? = null
        for (batch in songIds.distinct().chunked(TRACK_BATCH)) {
            when (val result = write(TRACKS_PATH, removeSongsPayload(playlistId, batch))) {
                is PlaylistWriteResult.Success -> count = result.raw.optInt("count", count ?: 0)
                else -> {
                    recordError("removeSongs", result)
                    return null
                }
            }
        }
        return count
    }

    /**
     * 删除歌单（单个）。参数名是 **`pid`**；传 `ids` 会 400「请求参数错误」。
     * 非本人 pid 返回 401「无权限操作歌单」，可用来判定归属。
     */
    suspend fun deletePlaylist(playlistId: Long): Boolean =
        write(DELETE_PATH, mapOf("pid" to playlistId.toString())).isSuccess()

    /**
     * 批量删除歌单（`/eapi/playlist/remove`，参数名是 **`ids`** 且为 JSON 串）。
     * 注意它**对非本人歌单返回 200 却什么都不做**，所以不能拿它判权限 —— 判权限用 [deletePlaylist]。
     */
    suspend fun removePlaylists(playlistIds: List<Long>): Boolean =
        write(REMOVE_PATH, mapOf("ids" to JSONArray(playlistIds).toString())).isSuccess()

    suspend fun renamePlaylist(playlistId: Long, name: String): Boolean =
        write(UPDATE_NAME_PATH, mapOf("id" to playlistId.toString(), "name" to name)).isSuccess()

    suspend fun updatePlaylistDesc(playlistId: Long, desc: String): Boolean =
        write(UPDATE_DESC_PATH, mapOf("id" to playlistId.toString(), "desc" to desc)).isSuccess()

    /** 改隐私，只接受 [PRIVACY_PUBLIC] / [PRIVACY_PRIVATE]。 */
    suspend fun updatePlaylistPrivacy(playlistId: Long, privacy: Int): Boolean {
        if (privacy != PRIVACY_PUBLIC && privacy != PRIVACY_PRIVATE) return false
        return write(UPDATE_PRIVACY_PATH, privacyPayload(playlistId, privacy)).isSuccess()
    }

    // ==================== 请求体构造（纯函数，单测直接断言） ====================

    /**
     * 加歌请求体。`op` 必须白名单化成 `add` —— 实测**任何非 `add` 的值（含空串、未知值）
     * 都会被服务端当成删除执行**，透传外部输入会静默删歌。
     */
    fun addSongsPayload(playlistId: Long, songIds: List<Long>): Map<String, String> = mapOf(
        "op" to "add",
        "pid" to playlistId.toString(),
        "trackIds" to JSONArray(songIds).toString(),
        "imme" to "true",
    )

    /** 移除歌曲请求体。同上，`op` 固定为 `del`。 */
    fun removeSongsPayload(playlistId: Long, songIds: List<Long>): Map<String, String> = mapOf(
        "op" to "del",
        "pid" to playlistId.toString(),
        "trackIds" to JSONArray(songIds).toString(),
        "imme" to "true",
    )

    /** 改隐私请求体；`privacy` 只接受 0/10，其它值由 [updatePlaylistPrivacy] 提前拒绝。 */
    fun privacyPayload(playlistId: Long, privacy: Int): Map<String, String> = mapOf(
        "id" to playlistId.toString(),
        "privacy" to privacy.toString(),
    )

    // ==================== 传输 ====================

    /** 最近一次失败的原始结果，供上层区分「限流」等场景做不同文案。 */
    @Volatile
    var lastError: PlaylistWriteResult? = null
        private set

    private suspend fun write(path: String, payload: Map<String, String>): PlaylistWriteResult =
        PlaylistWriteGate.run {
            withContext(Dispatchers.IO) {
                val body = payload.toMutableMap()
                // csrf_token 服务端不校验值，但带上最稳；身份 cookie 与它缺一不可，
                // 否则 403 illegal request!（实测：只带原 cookie 必 403）。
                RetrofitClient.getCsrfToken()?.takeIf { it.isNotEmpty() }?.let { body["csrf_token"] = it }
                Log.i(TAG, "write " + path + " keys=" + body.keys.sorted())
                try {
                    val response = RetrofitClient.eapiPost(
                        path,
                        body,
                        cookieOverride = ClientIdentity.withIdentity(RetrofitClient.getCookie()),
                    )
                    val text = response.body?.string()
                    val result = parseWriteResponse(response.code, text)
                    SessionMonitor.reportApiCode(result.businessCode)
                    result
                } catch (e: IOException) {
                    Log.w(TAG, "write " + path + " failed", e)
                    PlaylistWriteResult.NetworkError(e)
                }
            }
        }

    /**
     * 业务码 → 结果。纯函数，便于单测覆盖（写端点在真机上才跑）。
     *
     * 空 body 是 eapi 加密任何偏差（明文 / 垃圾 hex / 摘要错）的共同表现 —— HTTP 200、
     * 无任何报错信息，排查时先怀疑加密而不是网络。
     */
    fun parseWriteResponse(httpCode: Int, body: String?): PlaylistWriteResult {
        if (body.isNullOrBlank()) return PlaylistWriteResult.Failure(-1, "empty body", httpCode)
        val json = runCatching { JSONObject(body) }.getOrElse { e ->
            Log.w(TAG, "write response is not json: " + body.take(120), e)
            return PlaylistWriteResult.Failure(-1, "invalid json", httpCode)
        }
        val code = json.optInt("code", -1)
        val message = json.optString("message").ifEmpty { json.optString("msg") }
        return when (code) {
            200 -> PlaylistWriteResult.Success(json)
            // 502：歌单(内)歌曲重复 —— 两种措辞服务端都用过。
            502 -> PlaylistWriteResult.Duplicate
            405 -> PlaylistWriteResult.RateLimited
            403 -> PlaylistWriteResult.IllegalRequest
            else -> PlaylistWriteResult.Failure(code, message.ifEmpty { "unknown" }, httpCode)
        }
    }

    private fun PlaylistWriteResult.isSuccess(): Boolean = when (this) {
        is PlaylistWriteResult.Success -> true
        // 502 在「加歌」语义下是幂等成功；对删除/改名等操作不会出现，视为成功也无副作用。
        PlaylistWriteResult.Duplicate -> true
        else -> {
            recordError("write", this)
            false
        }
    }

    private fun recordError(op: String, result: PlaylistWriteResult) {
        lastError = result
        val detail = when (result) {
            is PlaylistWriteResult.Failure ->
                "code=" + result.code + " message=" + result.message + " http=" + result.httpCode
            is PlaylistWriteResult.NetworkError -> "network=" + result.error.message
            else -> result.toString()
        }
        Log.w(TAG, op + " failed: " + detail)
    }
}
