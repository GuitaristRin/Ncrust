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
    private const val SONG_URL_V1 = "https://interface3.music.163.com/eapi/song/enhance/player/url/v1"

    suspend fun fetch(songId: Long, level: String = "lossless"): SongUrlResult = withContext(Dispatchers.IO) {
        // Try the requested level first, then fall back down the quality ladder.
        val fallbackLevels = when (level) {
            "hires"    -> listOf("hires", "lossless", "exhigh", "higher", "standard")
            "lossless" -> listOf("lossless", "exhigh", "higher", "standard")
            "exhigh"   -> listOf("exhigh", "higher", "standard")
            "higher"   -> listOf("higher", "standard")
            else       -> listOf(level)
        }

        for (tryLevel in fallbackLevels) {
            try {
                val payload = buildPayload(songId, tryLevel)
                val response = RetrofitClient.eapiPost(SONG_URL_V1, payload)
                val body = response.body?.string() ?: continue
                Log.d(TAG, "eapi response ($tryLevel): $body")
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: continue
                if (data.length() > 0) {
                    val obj = data.getJSONObject(0)
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

        Log.e(TAG, "all levels exhausted, falling back to outer url")
        SongUrlResult("https://music.163.com/song/media/outer/url?id=$songId.mp3", "standard")
    }

    private fun buildPayload(songId: Long, level: String): Map<String, String> {
        val base = mutableMapOf(
            "ids" to JSONArray().put(songId).toString(),
            "level" to level
        )
        // FLAC encoding is only meaningful for lossless and hires tiers.
        if (level == "lossless" || level == "hires") {
            base["encodeType"] = "flac"
        }
        return base
    }
}
