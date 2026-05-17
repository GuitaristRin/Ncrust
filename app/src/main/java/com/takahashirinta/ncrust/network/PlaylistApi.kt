package com.takahashirinta.ncrust.network

import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object PlaylistApi {
    private const val USER_PLAYLIST_URL = "https://music.163.com/eapi/user/playlist"
    private const val PLAYLIST_DETAIL_URL = "https://music.163.com/eapi/v6/playlist/detail"

    /**
     * 获取当前登录用户的 UID
     */
    suspend fun getCurrentUserId(): Long = withContext(Dispatchers.IO) {
        val payload = emptyMap<String, String>()
        val response = RetrofitClient.eapiPost(
            "https://music.163.com/eapi/w/nuser/account/get",
            payload
        )
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val account = json.optJSONObject("account")
            ?: json.optJSONObject("profile")
            ?: throw Exception("no account data, body=$body")
        val userId = account.optLong("id", 0)
        if (userId == 0L) throw Exception("UID not found in: $body")
        userId
    }

    data class UserProfile(
        val userId: Long,
        val nickname: String,
        val avatarUrl: String
    )

    suspend fun getUserProfile(): UserProfile = withContext(Dispatchers.IO) {
        val payload = emptyMap<String, String>()
        val response = RetrofitClient.eapiPost(
            "https://music.163.com/eapi/w/nuser/account/get",
            payload
        )
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)

        val account = json.optJSONObject("account")
        val profile = json.optJSONObject("profile")

        UserProfile(
            userId = profile?.optLong("userId", account?.optLong("id", 0) ?: 0)
                ?: account?.optLong("id", 0) ?: 0,
            nickname = profile?.optString("nickname", "")?.ifEmpty { "用户" }
                ?: account?.optString("userName", "")?.ifEmpty { "用户" }
                ?: "用户",
            avatarUrl = profile?.optString("avatarUrl", "")
                ?: account?.optString("avatarUrl", "") ?: ""
        )
    }
    suspend fun getUserPlaylists(uid: Long, limit: Int = 100, offset: Int = 0): UserPlaylistResult = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "uid" to uid.toString(),
            "limit" to limit.toString(),
            "offset" to offset.toString(),
            "includeVideo" to "false"
        )
        val response = RetrofitClient.eapiPost(USER_PLAYLIST_URL, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val code = json.optInt("code", -1)
        if (code != 200) throw Exception("API error: code=$code")

        val playlists = mutableListOf<PlaylistInfo>()
        val playlistArray = json.optJSONArray("playlist") ?: JSONArray()
        for (i in 0 until playlistArray.length()) {
            val item = playlistArray.getJSONObject(i)
            playlists.add(
                PlaylistInfo(
                    id = item.optLong("id"),
                    name = item.optString("name"),
                    coverImgUrl = item.optString("coverImgUrl"),
                    trackCount = item.optInt("trackCount"),
                    creatorUserId = item.optJSONObject("creator")?.optLong("userId") ?: 0,
                    specialType = item.optInt("specialType"),
                    privacy = item.optInt("privacy")
                )
            )
        }

        UserPlaylistResult(
            playlists = playlists,
            total = json.optInt("total", 0),
            more = json.optBoolean("more", false)
        )
    }

    suspend fun getArtistDetail(artistId: Long): String = withContext(Dispatchers.IO) {
        val payload = mapOf("id" to artistId.toString())
        val response = RetrofitClient.eapiPost(
            "https://music.163.com/eapi/v1/artist/detail", payload
        )
        response.body?.string() ?: throw Exception("empty response")
    }

    suspend fun getArtistAlbums(artistId: Long): String = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "id" to artistId.toString(),
            "limit" to "50",
            "offset" to "0"
        )
        val response = RetrofitClient.eapiPost(
            "https://music.163.com/eapi/artist/albums", payload
        )
        response.body?.string() ?: throw Exception("empty response")
    }

    suspend fun getPlaylistDetail(playlistId: Long): List<SongItem> = withContext(Dispatchers.IO) {
        // n=1000 requests more full-detail tracks; server still caps at ~20 in `tracks`,
        // but always returns the complete list in `trackIds`.
        val payload = mapOf(
            "id" to playlistId.toString(),
            "n" to "1000",
            "s" to "0"
        )
        val response = RetrofitClient.eapiPost(PLAYLIST_DETAIL_URL, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val code = json.optInt("code", -1)
        if (code != 200) throw Exception("API error: code=$code")

        val playlistObj = json.optJSONObject("playlist") ?: return@withContext emptyList()

        // Parse the partial track objects that carry full detail (typically first ~20).
        val tracksMap = mutableMapOf<Long, SongItem>()
        val trackArray = playlistObj.optJSONArray("tracks")
        if (trackArray != null) {
            for (i in 0 until trackArray.length()) {
                val song = parseSongTrack(trackArray.getJSONObject(i))
                tracksMap[song.id] = song
            }
        }

        // Collect every ID in playlist order from the always-complete `trackIds` array.
        val allIds = mutableListOf<Long>()
        val trackIdsArray = playlistObj.optJSONArray("trackIds")
        if (trackIdsArray != null) {
            for (i in 0 until trackIdsArray.length()) {
                allIds.add(trackIdsArray.getJSONObject(i).optLong("id"))
            }
        }

        // If trackIds is absent (e.g. very short playlists already fully in tracks), use tracks order.
        if (allIds.isEmpty()) return@withContext tracksMap.values.toList()

        // Batch-fetch details for IDs not covered by the partial `tracks` array.
        val missingIds = allIds.filter { it !in tracksMap }
        val batchSize = 500
        for (start in missingIds.indices step batchSize) {
            val batch = missingIds.subList(start, minOf(start + batchSize, missingIds.size))
            fetchSongDetails(batch).forEach { tracksMap[it.id] = it }
        }

        // Return songs in the original playlist order defined by trackIds.
        allIds.mapNotNull { tracksMap[it] }
    }

    private fun parseSongTrack(track: JSONObject): SongItem {
        val artistArray = track.optJSONArray("ar")
        val artists: List<ArtistItem>? = artistArray?.let {
            (0 until it.length()).map { j ->
                ArtistItem(name = it.getJSONObject(j).optString("name"))
            }
        }
        val albumJson = track.optJSONObject("al")
        val album: AlbumItem? = albumJson?.let {
            AlbumItem(id = it.optLong("id"), name = it.optString("name"), picUrl = it.optString("picUrl"))
        }
        return SongItem(
            id = track.optLong("id"),
            name = track.optString("name"),
            artists = artists,
            album = album,
            duration = track.optLong("dt")
        )
    }

    private suspend fun fetchSongDetails(ids: List<Long>): List<SongItem> = withContext(Dispatchers.IO) {
        val cArray = JSONArray()
        ids.forEach { id -> cArray.put(JSONObject().put("id", id)) }
        val payload = mapOf("c" to cArray.toString())
        val response = RetrofitClient.eapiPost(
            "https://music.163.com/eapi/v3/song/detail",
            payload
        )
        val body = response.body?.string() ?: return@withContext emptyList()
        val songArray = JSONObject(body).optJSONArray("songs") ?: return@withContext emptyList()
        (0 until songArray.length()).map { i ->
            parseSongTrack(songArray.getJSONObject(i))
        }
    }

    data class PlaylistInfo(
        val id: Long,
        val name: String,
        val coverImgUrl: String,
        val trackCount: Int,
        val creatorUserId: Long,
        val specialType: Int,
        val privacy: Int
    )

    data class UserPlaylistResult(
        val playlists: List<PlaylistInfo>,
        val total: Int,
        val more: Boolean
    )

    data class PlaylistCard(
        val id: Long,
        val name: String,
        val coverUrl: String,
        val playCount: Long = 0,
        val trackCount: Int = 0
    )

    // ==================== Discovery ====================

    suspend fun getDailyRecommendSongs(): List<SongItem> = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPost(
            "https://music.163.com/eapi/v2/discovery/recommend/songs",
            emptyMap()
        )
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val arr = json.optJSONArray("recommend") ?: json.optJSONArray("data")
            ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            SongItem(
                id = s.optLong("id"),
                name = s.optString("name"),
                artists = s.optJSONArray("artists")?.let { ar ->
                    (0 until ar.length()).map { j ->
                        ArtistItem(name = ar.getJSONObject(j).optString("name"))
                    }
                },
                album = s.optJSONObject("album")?.let {
                    AlbumItem(id = it.optLong("id"), name = it.optString("name"), picUrl = it.optString("picUrl"))
                },
                duration = s.optLong("duration").takeIf { it != 0L }
            )
        }
    }

    suspend fun getRecommendPlaylists(): List<PlaylistCard> = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPost(
            "https://music.163.com/eapi/v1/discovery/recommend/resource",
            emptyMap()
        )
        val body = response.body?.string() ?: throw Exception("empty response")
        val arr = JSONObject(body).optJSONArray("recommend") ?: return@withContext emptyList()
        (0 until minOf(arr.length(), 10)).map { i ->
            val item = arr.getJSONObject(i)
            PlaylistCard(
                id = item.optLong("id"),
                name = item.optString("name"),
                coverUrl = item.optString("picUrl"),
                playCount = item.optLong("playCount"),
                trackCount = if (item.optString("name") == "私人雷达") 35 else item.optInt("trackCount")
            )
        }
    }

    suspend fun getTopSongs(limit: Int = 30, offset: Int = 0): List<SongItem> = withContext(Dispatchers.IO) {
        val body = RetrofitClient.get(
            "https://music.163.com/api/v1/discovery/new/songs?limit=$limit&offset=$offset"
        )
        val json = JSONObject(body)
        val arr = json.optJSONArray("data") ?: json.optJSONArray("songs")
            ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            SongItem(
                id = s.optLong("id"),
                name = s.optString("name"),
                artists = s.optJSONArray("artists")?.let { ar ->
                    (0 until ar.length()).map { j ->
                        ArtistItem(name = ar.getJSONObject(j).optString("name"))
                    }
                },
                album = s.optJSONObject("album")?.let {
                    AlbumItem(id = it.optLong("id"), name = it.optString("name"), picUrl = it.optString("picUrl"))
                },
                duration = s.optLong("duration").takeIf { it != 0L }
            )
        }
    }

    suspend fun getPersonalFm(): List<SongItem> = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPost(
            "https://music.163.com/eapi/v1/radio/get",
            emptyMap()
        )
        val body = response.body?.string() ?: throw Exception("empty response")
        val arr = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            SongItem(
                id = s.optLong("id"),
                name = s.optString("name"),
                artists = s.optJSONArray("ar")?.let { ar ->
                    (0 until ar.length()).map { j ->
                        ArtistItem(name = ar.getJSONObject(j).optString("name"))
                    }
                },
                album = s.optJSONObject("al")?.let {
                    AlbumItem(id = it.optLong("id"), name = it.optString("name"), picUrl = it.optString("picUrl"))
                },
                duration = s.optLong("dt").takeIf { it != 0L }
            )
        }
    }

    // FM垃圾桶：对当前 FM 歌曲执行不喜欢操作
    suspend fun fmTrash(songId: Long): Boolean = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPost(
            "https://music.163.com/eapi/radio/trash/add",
            mapOf("songId" to songId.toString(), "alg" to "itembased", "time" to "25")
        )
        val body = response.body?.string() ?: return@withContext false
        JSONObject(body).optInt("code", -1) == 200
    }
}