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
        try {
            val payload = mapOf(
                "ids" to JSONArray().put(songId).toString(),
                "level" to level,
                "encodeType" to "flac"
            )
            val response = RetrofitClient.eapiPost(SONG_URL_V1, payload)
            val body = response.body?.string() ?: throw Exception("empty response")
            Log.d(TAG, "eapi response: $body")
            val json = JSONObject(body)
            val data = json.getJSONArray("data")
            if (data.length() > 0) {
                val obj = data.getJSONObject(0)
                val url = obj.optString("url")
                val actualLevel = obj.optString("level", level)
                if (!url.isNullOrEmpty()) {
                    Log.d(TAG, "got url: $url  actualLevel: $actualLevel")
                    return@withContext SongUrlResult(url, actualLevel)
                }
            }
            throw Exception("url is empty, code: ${json.optInt("code")}")
        } catch (e: Exception) {
            Log.e(TAG, "eapi failed, fallback to outer url", e)
            SongUrlResult("https://music.163.com/song/media/outer/url?id=$songId.mp3", level)
        }
    }
}
