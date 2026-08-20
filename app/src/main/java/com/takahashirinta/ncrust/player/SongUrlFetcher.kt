package com.takahashirinta.ncrust.player

import android.util.Log
import com.takahashirinta.ncrust.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SongUrlResult(val url: String, val actualLevel: String)

object SongUrlFetcher {
    private const val TAG = "SongUrlFetcher"
    private const val SONG_URL_PATH = "/eapi/song/enhance/player/url/v1"

    // Returns null when no level yields a playable URL (e.g. VIP-only song without a
    // subscription, or no valid session). Callers must skip the song instead of playing.
    suspend fun fetch(songId: Long, level: String = "lossless"): SongUrlResult? = withContext(Dispatchers.IO) {
        // Try the requested level first, then fall back down the quality ladder.
        val fallbackLevels = when (level) {
            "dolby"    -> listOf("dolby", "hires", "lossless", "exhigh", "higher", "standard")
            "jyeffect" -> listOf("jyeffect", "lossless", "exhigh", "higher", "standard")
            "hires"    -> listOf("hires", "lossless", "exhigh", "higher", "standard")
            "lossless" -> listOf("lossless", "exhigh", "higher", "standard")
            "exhigh"   -> listOf("exhigh", "higher", "standard")
            "higher"   -> listOf("higher", "standard")
            "standard" -> listOf("standard")
            else       -> listOf(level, "lossless", "exhigh", "higher", "standard")
        }

        for (tryLevel in fallbackLevels) {
            try {
                val payload = buildPayload(songId, tryLevel)
                val response = RetrofitClient.eapiPost(SONG_URL_PATH, payload, useInterface = true)
                val body = response.body?.string() ?: continue
                Log.d(TAG, "eapi response ($tryLevel): $body")
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: continue
                if (data.length() > 0) {
                    val obj = data.getJSONObject(0)
                    // code != 200 means the level is unavailable (404 = no resource / not entitled).
                    // There is no point accepting such an entry, so advance down the ladder.
                    if (obj.optInt("code", 200) != 200) continue
                    val url = obj.optString("url")
                    val actualLevel = obj.optString("level", tryLevel)
                    if (!url.isNullOrEmpty()) {
                        Log.d(TAG, "got url: $url  actualLevel: $actualLevel  requested: $level")
                        return@withContext SongUrlResult(url, actualLevel)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed for level=$tryLevel", e)
            }
        }

        // 所有档位都取不到可播放的 URL。绝不要用 https://music.163.com/song/media/outer/url?id=X.mp3
        // 兜底 —— 那个旧端点对无版权/需会员的歌曲返回 302→404 的 HTML 页面，ExoPlayer 拿到非音频流
        // 会无限缓冲（"卡住"）。这里直接返回 null，让上层跳歌而不是播放坏链接。
        Log.e(TAG, "no playable url for songId=$songId at any level")
        null
    }

    private fun buildPayload(songId: Long, level: String): Map<String, String> {
        // 官方客户端 header 字段,声明 PC 端并携带随机 requestId,保证杜比等音质返回正常码率。
        val config = JSONObject()
            .put("os", "pc")
            .put("appver", "")
            .put("osver", "")
            .put("deviceId", "pyncm!")
            .put("requestId", (20_000_000..30_000_000).random().toString())

        val base = mutableMapOf(
            "ids" to JSONArray().put(songId).toString(),
            "level" to level,
            "header" to config.toString(),
        )
        // 杜比全景声必须以 mp4 容器输出(EAC3),其余音质用 FLAC。
        base["encodeType"] = if (level == "dolby") "mp4" else "flac"
        return base
    }
}
