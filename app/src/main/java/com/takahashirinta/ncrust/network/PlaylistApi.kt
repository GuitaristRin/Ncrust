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
        val payload = mapOf(
            "id" to playlistId.toString(),
            "n" to "50",
            "s" to "0"
        )
        val response = RetrofitClient.eapiPost(PLAYLIST_DETAIL_URL, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val json = JSONObject(body)
        val code = json.optInt("code", -1)
        if (code != 200) throw Exception("API error: code=$code")

        val songs = mutableListOf<SongItem>()
        val playlistObj = json.optJSONObject("playlist") ?: return@withContext songs
        val trackArray = playlistObj.optJSONArray("tracks") ?: return@withContext songs

        for (i in 0 until trackArray.length()) {
            val track = trackArray.getJSONObject(i)
            val artistArray = track.optJSONArray("ar")
            val artists: List<ArtistItem>? = if (artistArray != null) {
                val list = mutableListOf<ArtistItem>()
                for (j in 0 until artistArray.length()) {
                    list.add(ArtistItem(name = artistArray.getJSONObject(j).optString("name")))
                }
                list
            } else null

            val albumJson = track.optJSONObject("al")
            val album: AlbumItem? = if (albumJson != null) {
                AlbumItem(
                    id = albumJson.optLong("id"),
                    name = albumJson.optString("name"),
                    picUrl = albumJson.optString("picUrl")
                )
            } else null

            songs.add(
                SongItem(
                    id = track.optLong("id"),
                    name = track.optString("name"),
                    artists = artists,
                    album = album,
                    duration = track.optLong("dt")
                )
            )
        }
        songs
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
}