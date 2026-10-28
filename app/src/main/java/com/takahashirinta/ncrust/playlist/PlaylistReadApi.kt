package com.takahashirinta.ncrust.playlist

import com.takahashirinta.ncrust.account.SessionMonitor
import com.takahashirinta.ncrust.network.PlaylistApi.PlaylistInfo
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.ClientIdentity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 所有请求显式绑定同一凭证；换账号后旧响应不能改变新账号的会话状态。 */
class PlaylistReadApi(private val cookie: String) {
    private suspend fun read(path: String, payload: Map<String, String>): JSONObject = withContext(Dispatchers.IO) {
        RetrofitClient.eapiPost(path, payload, cookieOverride = ClientIdentity.withIdentity(cookie)).use { response ->
            if (!response.isSuccessful) throw PlaylistRequestException(PlaylistProblem.NETWORK)
            val body = response.body?.string() ?: throw PlaylistRequestException(PlaylistProblem.NETWORK)
            val json = JSONObject(body)
            val code = json.optInt("code", -1)
            if (cookie == RetrofitClient.getCookie()) SessionMonitor.reportApiCode(code)
            if (code != 200) throw PlaylistRequestException(when (code) {
                301 -> PlaylistProblem.LOGIN
                401, 403 -> PlaylistProblem.PERMISSION
                405 -> PlaylistProblem.RATE_LIMIT
                else -> PlaylistProblem.REJECTED
            })
            json
        }
    }

    suspend fun userId(): Long {
        val json = read("/eapi/w/nuser/account/get", emptyMap())
        val id = json.optJSONObject("account")?.optLong("id", 0)?.takeIf { it > 0 }
            ?: json.optJSONObject("profile")?.optLong("userId", 0)?.takeIf { it > 0 }
        return id ?: throw PlaylistRequestException(PlaylistProblem.LOGIN)
    }

    suspend fun snapshot(id: Long): PlaylistSnapshot {
        val json = read("/eapi/v6/playlist/detail", mapOf("id" to "$id", "n" to "1", "s" to "0"))
        val value = json.optJSONObject("playlist") ?: throw PlaylistRequestException(PlaylistProblem.INCOMPLETE)
        return parseSnapshot(value).also {
            if (it.info.id != id) throw PlaylistRequestException(PlaylistProblem.INCOMPLETE)
        }
    }

    suspend fun collection(userId: Long): PlaylistCollection {
        val items = linkedMapOf<Long, PlaylistInfo>()
        val seen = hashSetOf<Long>()
        for (page in 0 until 50) {
            val json = read("/eapi/user/playlist", mapOf("uid" to "$userId", "limit" to "100", "offset" to "${page * 100}", "includeVideo" to "false"))
            val array = json.optJSONArray("playlist") ?: return PlaylistCollection(items.values.toList(), false)
            var added = 0
            for (index in 0 until array.length()) {
                val info = parseInfo(array.getJSONObject(index))
                if (info.id > 0 && seen.add(info.id)) added++
                if (info.id > 0 && info.specialType == 0) items[info.id] = info
            }
            if (json.has("more") && !json.optBoolean("more")) return PlaylistCollection(items.values.toList(), true)
            if (added == 0 || array.length() == 0 || !json.has("more")) break
        }
        return PlaylistCollection(items.values.toList(), false)
    }

    companion object {
        fun parseInfo(value: JSONObject) = PlaylistInfo(
            id = value.optLong("id"), name = value.optString("name"), coverImgUrl = value.optString("coverImgUrl"),
            trackCount = value.optInt("trackCount", -1), creatorUserId = value.optJSONObject("creator")?.optLong("userId") ?: 0,
            specialType = value.optInt("specialType", -1), privacy = value.optInt("privacy", -1),
            subscribed = value.optBoolean("subscribed"), description = if (value.isNull("description")) "" else value.optString("description")
        )

        fun parseSnapshot(value: JSONObject): PlaylistSnapshot {
            val info = parseInfo(value)
            val array = value.optJSONArray("trackIds")
            val ids = if (array == null) emptyList() else (0 until array.length()).map { array.optJSONObject(it)?.optLong("id") ?: 0 }
            val complete = array != null && info.trackCount >= 0 && ids.size == info.trackCount && ids.all { it > 0 } && ids.distinct().size == ids.size
            return PlaylistSnapshot(info, info.description, ids.filter { it > 0 }.distinct(), complete)
        }
    }
}
