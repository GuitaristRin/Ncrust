package com.takahashirinta.ncrust.network

import android.util.Log
import androidx.compose.runtime.Immutable
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.network.crypto.EapiCrypto
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

object PlaylistApi {
    private const val USER_PLAYLIST_PATH = "/eapi/user/playlist"
    private const val PLAYLIST_DETAIL_PATH = "/eapi/v6/playlist/detail"
    private const val ACCOUNT_GET_PATH = "/eapi/w/nuser/account/get"

    /**
     * 获取当前登录用户的 UID
     */
    suspend fun getCurrentUserId(): Long = withContext(Dispatchers.IO) {
        val payload = emptyMap<String, String>()
        val response = RetrofitClient.eapiPost(ACCOUNT_GET_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val account = json.optJSONObject("account")
            ?: json.optJSONObject("profile")
            ?: throw Exception("no account data, body=$body")
        val userId = account.optLong("id", 0)
        if (userId == 0L) throw Exception("UID not found in: $body")
        userId
    }

    @Immutable
    data class UserProfile(
        val userId: Long,
        val nickname: String,
        val avatarUrl: String
    )

    suspend fun getUserProfile(): UserProfile = withContext(Dispatchers.IO) {
        val payload = emptyMap<String, String>()
        val response = RetrofitClient.eapiPost(ACCOUNT_GET_PATH, payload)
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
        val response = RetrofitClient.eapiPost(USER_PLAYLIST_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val code = json.optInt("code", -1)
        if (code != 200) throw Exception("API error: code=$code")

        val playlists = mutableListOf<PlaylistInfo>()
        val playlistArray = json.optJSONArray("playlist") ?: JSONArray()
        for (i in 0 until playlistArray.length()) {
            val item = playlistArray.getJSONObject(i)
            // 红心歌单（「我喜欢的音乐」等 specialType!=0 的特殊歌单）不作为普通歌单收藏展示，跳过。
            if (item.optInt("specialType") != 0) continue
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
        val response = RetrofitClient.eapiPost("/eapi/v1/artist/detail", payload)
        response.body?.string() ?: throw Exception("empty response")
    }

    suspend fun getArtistAlbums(artistId: Long): String = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "id" to artistId.toString(),
            "limit" to "50",
            "offset" to "0"
        )
        val response = RetrofitClient.eapiPost("/eapi/artist/albums", payload)
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
        val response = RetrofitClient.eapiPost(PLAYLIST_DETAIL_PATH, payload)
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
            val fetched = fetchSongDetails(batch)
            if (fetched.isEmpty() && batch.isNotEmpty()) {
                Log.w("PlaylistApi", "batch of ${batch.size} songs returned empty from server")
            }
            fetched.forEach { tracksMap[it.id] = it }
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

    private suspend fun fetchSongDetails(ids: List<Long>): List<SongItem> = withContext(Dispatchers.IO) {        val cArray = JSONArray()
        ids.forEach { id -> cArray.put(JSONObject().put("id", id)) }
        val payload = mapOf("c" to cArray.toString())
        // Single retry with 500ms delay to survive transient network blips on large playlists.
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                val response = RetrofitClient.eapiPost("/eapi/v3/song/detail", payload)
                val body = response.body?.string() ?: return@repeat
                val songArray = JSONObject(body).optJSONArray("songs") ?: return@repeat
                return@withContext (0 until songArray.length()).map { i ->
                    parseSongTrack(songArray.getJSONObject(i))
                }
            } catch (e: Exception) {
                lastError = e
                Log.w("PlaylistApi", "fetchSongDetails attempt ${attempt + 1} failed: ${e.message}")
                if (attempt == 0) delay(500)
            }
        }
        lastError?.let { Log.e("PlaylistApi", "fetchSongDetails gave up after retry", it) }
        emptyList()
    }

    @Immutable
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

    @Immutable
    data class PlaylistCard(
        val id: Long,
        val name: String,
        val coverUrl: String,
        val playCount: Long = 0,
        val trackCount: Int = 0
    )

    // ==================== Discovery ====================

    suspend fun getDailyRecommendSongs(): List<SongItem> = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPost("/eapi/v2/discovery/recommend/songs", emptyMap())
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
        val response = RetrofitClient.eapiPost("/eapi/v1/discovery/recommend/resource", emptyMap())
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
        val body = RetrofitClient.get("/api/v1/discovery/new/songs?limit=$limit&offset=$offset")
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
        val response = RetrofitClient.eapiPost("/eapi/v1/radio/get", emptyMap())
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
            "/eapi/radio/trash/add",
            mapOf("songId" to songId.toString(), "alg" to "itembased", "time" to "25")
        )
        val body = response.body?.string() ?: return@withContext false
        JSONObject(body).optInt("code", -1) == 200
    }

    // ==================== 云端收藏（收藏单曲 / 收藏专辑） ====================

    /**
     * 找到「我喜欢的音乐」（红心歌单）的 playlist id。
     *
     * 该歌单在 /eapi/user/playlist 中作为用户自己的特殊歌单出现（specialType != 0，
     * 普通自建歌单为 0），正是收藏页单曲 tab 的数据源。与官方 weapi 的 likelist
     * （/api/song/like/get，eapi 加密）等价，但走已证明可用的 playlist-detail 路径。
     */
    suspend fun getLikedPlaylistId(uid: Long): Long? = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "uid" to uid.toString(),
            "limit" to "200",
            "offset" to "0",
            "includeVideo" to "false"
        )
        val response = RetrofitClient.eapiPost(USER_PLAYLIST_PATH, payload)
        val body = response.body?.string() ?: return@withContext null
        val json = JSONObject(body)
        val arr = json.optJSONArray("playlist") ?: return@withContext null
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.optInt("specialType") != 0) return@withContext item.optLong("id")
        }
        // 兜底：按名字识别「我喜欢的音乐」
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.optString("name").contains("我喜欢的音乐")) return@withContext item.optLong("id")
        }
        null
    }

    /** 获取「我喜欢的音乐」全部单曲 ID（红心歌单 trackIds，有序、轻量、不会拉全部详情）。 */
    suspend fun getLikedTrackIds(uid: Long): List<Long> = withContext(Dispatchers.IO) {
        val playlistId = getLikedPlaylistId(uid) ?: return@withContext emptyList()
        val payload = mapOf(
            "id" to playlistId.toString(),
            "n" to "1000",
            "s" to "0"
        )
        val response = RetrofitClient.eapiPost(PLAYLIST_DETAIL_PATH, payload)
        val body = response.body?.string() ?: return@withContext emptyList()
        val json = JSONObject(body)
        val playlistObj = json.optJSONObject("playlist") ?: return@withContext emptyList()
        val trackIds = playlistObj.optJSONArray("trackIds") ?: return@withContext emptyList()
        return@withContext (0 until trackIds.length()).map { trackIds.getJSONObject(it).optLong("id") }
    }

    /** 按 ID 批量拉取单曲详情（eapi/v3/song/detail），供收藏单曲分页 lazy 加载用。 */
    suspend fun getSongsByIds(ids: List<Long>): List<SongItem> = fetchSongDetails(ids)

    /**
     * 收藏(like=true) / 取消收藏(false) 单曲。
     *
     * 走官方安卓客户端协议：eapi `/eapi/radio/like` + 客户端身份头 + 真随机 deviceId，
     * 并对**加密响应**做 AES 解密（eapi 写接口返回加密 JSON，读取接口为明文）。
     *
     * 注意：like 写操作受网易账号/IP 级风险控制，本账号四种协议变体(eapi 最小/eapi+PC 指纹/
     * eapi+安卓身份/经典 weapi)均被 `-460「检测到您的网络环境存在风险」`或异常响应拦截而
     * 读取全部正常——这是服务端风控，非本实现问题。真实官方 like 协议待后续抓包确认。
     */
    suspend fun likeSong(songId: Long, like: Boolean): Boolean = withContext(Dispatchers.IO) {
        val header = JSONObject()
            .put("os", "android")
            .put("appver", BuildConfig.VERSION_NAME)
            .put("osver", android.os.Build.VERSION.RELEASE.orEmpty())
            .put("deviceId", (0 until 20).joinToString("") { "0123456789abcdef"[(Math.random() * 16).toInt()].toString() })
            .put("requestId", (20_000_000..30_000_000).random().toString())
        val payload = mapOf(
            "alg" to "itembased",
            "trackId" to songId.toString(),
            "like" to like.toString(),
            "time" to (System.currentTimeMillis() / 1000).toString(),
            "e_r" to "TRUE",
            "header" to header.toString(),
            "csrf_token" to (RetrofitClient.getCsrfToken().orEmpty())
        )
        val http = try {
            RetrofitClient.eapiPostClientIdentity("/eapi/radio/like", payload)
        } catch (e: Throwable) {
            Log.w("PlaylistApi", "likeSong req failed id=$songId like=$like", e)
            return@withContext false
        }
        val raw = try { http.body?.bytes() } catch (_: Throwable) { null } ?: return@withContext false
        val plain = EapiCrypto.decryptResponse(java.util.Base64.getEncoder().encodeToString(raw))
        val jsonText = plain.ifEmpty { String(raw) }
        val code = try { JSONObject(jsonText).optInt("code", -1) } catch (_: Throwable) { -1 }
        Log.i("PlaylistApi", "likeSong(eapi/client) id=$songId like=$like code=$code")
        code == 200
    }

    /** 收藏的专辑（云端的「我收藏的专辑」，weapi）。 */
    @Immutable
    data class CloudAlbum(
        val albumId: Long,
        val name: String,
        val artist: String,
        val picUrl: String,
        val songCount: Int
    )

    suspend fun getSubscribedAlbums(limit: Int = 100, offset: Int = 0): List<CloudAlbum> = withContext(Dispatchers.IO) {
        val payload = mapOf(
            "limit" to limit.toString(),
            "offset" to offset.toString(),
            "total" to "true"
        )
        // 客户端(接口域)用的是 eapi 端点 /eapi/album/sublist；weapi 那条网页端读取为空，
        // 故改用 eapi 读「我收藏的专辑」。eapi 返回 data 直接是专辑数组(非 weapi 的 data.albums)。
        val response = RetrofitClient.eapiPost("/eapi/album/sublist", payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val arr: JSONArray = when {
            json.optJSONArray("data") != null -> json.optJSONArray("data")
            json.optJSONObject("data") != null -> json.optJSONObject("data").optJSONArray("albums")
            else -> return@withContext emptyList()
        }
        (0 until arr.length()).map { i ->
            val a = arr.getJSONObject(i)
            CloudAlbum(
                albumId = a.optLong("id"),
                name = a.optString("name"),
                artist = a.optJSONArray("artists")
                    ?.takeIf { it.length() > 0 }
                    ?.getJSONObject(0)?.optString("name")
                    ?: a.optJSONObject("artist")?.optString("name") ?: "",
                picUrl = a.optString("picUrl"),
                songCount = a.optInt("size")
            )
        }
    }

    /** 收藏(sub=true) / 取消收藏(false) 专辑（eapi，与客户端一致）。 */
    suspend fun subAlbum(albumId: Long, sub: Boolean): Boolean = withContext(Dispatchers.IO) {
        val action = if (sub) "sub" else "unsub"
        val payload = mapOf("id" to albumId.toString())
        val response = RetrofitClient.eapiPost("/eapi/album/$action", payload)
        val body = response.body?.string() ?: return@withContext false
        JSONObject(body).optInt("code", -1) == 200
    }
}