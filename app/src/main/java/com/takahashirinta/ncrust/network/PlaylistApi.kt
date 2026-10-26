package com.takahashirinta.ncrust.network

import android.util.Log
import androidx.compose.runtime.Immutable
import com.takahashirinta.ncrust.auth.QrLoginProtocol
import com.google.gson.Gson
import com.takahashirinta.ncrust.account.AccountGetResponse
import com.takahashirinta.ncrust.account.SessionMonitor
import com.takahashirinta.ncrust.account.SongPrivilege
import com.takahashirinta.ncrust.account.VipInfoResponse
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
    /** 会员状态（weapi）。规格：`spec/api/entitlement.md` §VIP。 */
    private const val VIP_INFO_PATH = "/api/music-vip-membership/client/vip/info"

    // 会话判定用的响应模型（account 包）走 Gson 映射：夹具单测用同一套 @SerializedName
    // 解析 spec/fixtures/entitlement 下的响应，字段映射错了单测就会红。
    private val gson = Gson()

    /**
     * 业务码里唯一需要在网络层就地处理的是 301（用户未登录）：立即把会话状态转 `Expired`，
     * 界面据此显示失效横幅。登录失效时 `account/get` 仍返回 200，只有这类需登录接口会 301，
     * 所以判定必须在读到业务码的地方做。
     */
    private fun reportBusinessCode(code: Int) {
        SessionMonitor.reportApiCode(code)
    }

    /**
     * `account/get`（会话判定用）。
     * 注意：登录失效时它**仍然 200**，只是 `account`/`profile` 为 null —— 判定见
     * `deriveSession`，不能只看 HTTP 状态码。
     */
    suspend fun getAccount(): AccountGetResponse = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPost(ACCOUNT_GET_PATH, emptyMap())
        val body = response.body?.string() ?: throw Exception("empty response")
        val parsed = gson.fromJson(body, AccountGetResponse::class.java)
            ?: throw Exception("account/get 解析失败")
        reportBusinessCode(parsed.code ?: -1)
        parsed
    }

    /**
     * 黑胶 VIP / 音乐包状态。只应在会话 `Valid` 时调用。
     * 失败（网络异常 / 非 200 / 301）由调用方处理成「未知」——**绝不**显示成「未开通」。
     */
    suspend fun getVipInfo(userId: Long): VipInfoResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("userId", userId).toString()
        val response = RetrofitClient.weapiPost(VIP_INFO_PATH, payload)
        val body = response.body?.string() ?: throw Exception("empty response")
        val parsed = gson.fromJson(body, VipInfoResponse::class.java)
            ?: throw Exception("vip/info 解析失败")
        reportBusinessCode(parsed.code ?: -1)
        parsed
    }

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
        reportBusinessCode(json.optInt("code", -1))

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
                    privacy = item.optInt("privacy"),
                    subscribed = item.optBoolean("subscribed", false)
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
        // 权益信息与 tracks[] 并列，按 id 关联到每首歌（无版权判定靠它）。
        val privileges = parsePrivileges(playlistObj.optJSONArray("privileges"))

        // Parse the partial track objects that carry full detail (typically first ~20).
        val tracksMap = mutableMapOf<Long, SongItem>()
        val trackArray = playlistObj.optJSONArray("tracks")
        if (trackArray != null) {
            for (i in 0 until trackArray.length()) {
                val song = parseSongTrack(trackArray.getJSONObject(i))
                tracksMap[song.id] = privileges[song.id]?.let { song.copy(privilege = it) } ?: song
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
                val a = it.getJSONObject(j)
                // id 带出来供"转到歌手"回调直接跳转, 免二次 detail 查询
                ArtistItem(id = a.optLong("id").takeIf { v -> v != 0L }, name = a.optString("name"))
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

    /**
     * 详情接口的权益数组（`v3/song/detail`、`v6/playlist/detail` 都把它放在与歌曲并列的位置），
     * 按 id 关联到每首歌。缺 `st` 时按 0 处理——**不能**把"没有这个字段"当成"无版权"。
     */
    private fun parsePrivileges(array: JSONArray?): Map<Long, SongPrivilege> {
        if (array == null) return emptyMap()
        val map = mutableMapOf<Long, SongPrivilege>()
        for (i in 0 until array.length()) {
            val p = array.optJSONObject(i) ?: continue
            val id = p.optLong("id", 0L)
            if (id <= 0L) continue
            map[id] = SongPrivilege(
                st = p.optInt("st", 0),
                pl = p.optLong("pl", 0L),
                fee = p.optInt("fee", 0)
            )
        }
        return map
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
                val json = JSONObject(body)
                reportBusinessCode(json.optInt("code", -1))
                val songArray = json.optJSONArray("songs") ?: return@repeat
                val privileges = parsePrivileges(json.optJSONArray("privileges"))
                return@withContext (0 until songArray.length()).map { i ->
                    val song = parseSongTrack(songArray.getJSONObject(i))
                    privileges[song.id]?.let { song.copy(privilege = it) } ?: song
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
        val privacy: Int,
        /** 是否为「收藏的他人歌单」。写接口只对本人自建（`!subscribed`）有效。 */
        val subscribed: Boolean = false
    ) {
        /**
         * 是否本人自建（可写）：`creator.userId == 我 && !subscribed`。
         *
         * **不能只看 `creatorUserId`**：收藏来的他人歌单里 `creator.userId` 是**原作者**，
         * 据此判定会把他人歌单当自建，写操作要么 404（曲目操作）要么 401（删除）。
         */
        fun isOwnedBy(userId: Long): Boolean = !subscribed && creatorUserId == userId
    }

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
        // 登录失效时这个接口**仍然 200**，只是返回非个性化的通用列表（规格 §问题）；
        // 真正会 301 的是收藏等私有接口，但代码里统一上报：301 即转 Expired。
        val code = json.optInt("code", -1)
        reportBusinessCode(code)
        if (code != 200) return@withContext emptyList()
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

    /**
     * 私人 FM 电台歌曲（客户端端点 /eapi/v1/radio/get, 返回 `data[]` 每项
     * 是 `{ "song": {...} }` 嵌套, 与 recommend 的老平铺结构不同）。
     *
     * 这是真正意义上的 FM: 由网易按收听历史/偏好生成, 不是"相似歌曲"或
     * "每日推荐"——Infinity 无限播放应该用这个作为主数据源。
     */
    suspend fun getPersonalFm(): List<SongItem> = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPost("/eapi/v1/radio/get", emptyMap())
        val body = response.body?.string() ?: throw Exception("empty response")
        val arr = JSONObject(body).optJSONArray("data") ?: return@withContext emptyList()
        (0 until arr.length()).map { i ->
            // data[i].song 是完整歌曲对象（含 id/name/ar/al/dt）; 某些响应直接把歌曲摊在 data[i] 顶层
            val s = arr.getJSONObject(i).optJSONObject("song") ?: arr.getJSONObject(i)
            SongItem(
                id = s.optLong("id"),
                name = s.optString("name"),
                artists = (s.optJSONArray("ar") ?: s.optJSONArray("artists"))?.let { ar ->
                    (0 until ar.length()).map { j ->
                        ArtistItem(
                            id = ar.getJSONObject(j).optLong("id").takeIf { it != 0L },
                            name = ar.getJSONObject(j).optString("name")
                        )
                    }
                },
                album = (s.optJSONObject("al") ?: s.optJSONObject("album"))?.let {
                    AlbumItem(
                        id = it.optLong("id").takeIf { v -> v != 0L },
                        name = it.optString("name"),
                        picUrl = it.optString("picUrl")
                    )
                },
                duration = (s.optLong("dt").takeIf { it != 0L }
                    ?: s.optLong("duration").takeIf { it != 0L })
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

    /**
     * 相似歌曲（Infinity 无限播放的数据源）。
     *
     * 主端点用客户端 eapi /eapi/v1/discovery/similarSong；该端点若失效/返回空
     * （实测会退化成每日推荐），回退官方 weapi /api/discovery/simiSong。
     * 返回歌曲是老格式(artists/album/duration), 解析时新旧字段都兜底;
     * artists[].id 带出来, 供后续"转到歌手/专辑"回调直接使用。
     */
    suspend fun getSimilarSongs(songId: Long, limit: Int = 20): List<SongItem> = withContext(Dispatchers.IO) {
        val payloadJson = JSONObject(
            mapOf("songid" to songId.toString(), "limit" to limit.toString(), "offset" to "0")
        ).toString()

        val eapi = runCatching {
            val response = RetrofitClient.eapiPost(
                "/eapi/v1/discovery/similarSong",
                mapOf("songid" to songId.toString(), "limit" to limit.toString(), "offset" to "0")
            )
            response.body?.string()?.let { parseSimilarSongs(it) } ?: emptyList()
        }.getOrDefault(emptyList())
        if (eapi.isNotEmpty()) return@withContext eapi

        runCatching {
            val response = RetrofitClient.weapiPost("/api/discovery/simiSong", payloadJson)
            response.body?.string()?.let { parseSimilarSongs(it) } ?: emptyList()
        }.getOrDefault(emptyList())
    }

    private fun parseSimilarSongs(body: String): List<SongItem> {
        val arr = JSONObject(body).optJSONArray("songs") ?: return emptyList()
        return (0 until arr.length()).map { i ->
            val s = arr.getJSONObject(i)
            SongItem(
                id = s.optLong("id"),
                name = s.optString("name"),
                artists = (s.optJSONArray("artists") ?: s.optJSONArray("ar"))?.let { ar ->
                    (0 until ar.length()).map { j ->
                        val a = ar.getJSONObject(j)
                        ArtistItem(id = a.optLong("id").takeIf { it != 0L }, name = a.optString("name"))
                    }
                },
                album = (s.optJSONObject("album") ?: s.optJSONObject("al"))?.let {
                    AlbumItem(id = it.optLong("id").takeIf { v -> v != 0L }, name = it.optString("name"), picUrl = it.optString("picUrl"))
                },
                duration = (s.optLong("duration").takeIf { it != 0L } ?: s.optLong("dt")).takeIf { it != 0L }
            )
        }
    }

    // ==================== 原生扫码登录（平板 / 大屏） ====================

    data class LoginQrKey(
        val unikey: String,
        val qrurl: String,
        val qrimg: String?
    )

    /**
     * 申请二维码登录 key。
     *
     * 走 **eapi + PC 客户端身份 + type=3**（打 interfacepc，协议见 `spec/api/endpoints.md`）。
     * 旧的 weapi 形态（type=1 + noCheckToken，二维码带 web 会话的 chainId）2026-10 实测被回
     * `-462` 风控，二维码根本出不来。二维码内容也不再带 chainId——PC 身份不用它。
     */
    suspend fun getLoginQrKey(): LoginQrKey? = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPostPc(QrLoginProtocol.UNIKEY_PATH, QrLoginProtocol.unikeyFields())
        val body = response.body?.string() ?: return@withContext null
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext null
        if (json.optInt("code", -1) != 200) return@withContext null
        val unikey = json.optString("unikey")
        if (unikey.isEmpty()) return@withContext null
        LoginQrKey(
            unikey = unikey,
            qrurl = QrLoginProtocol.qrContent(unikey),
            qrimg = json.optString("qrimg", "").takeIf { it.isNotEmpty() }
        )
    }

    data class LoginQrStatus(val code: Int, val cookie: String?)

    /**
     * 轮询二维码状态。code: 800=过期, 801=待扫码, 802=已扫码待确认, 803=成功。
     *
     * 803 时会话 cookie 在响应的 Set-Cookie 头里（同名取**最后一个非空** MUSIC_U），
     * 拼出完整 cookie 串交上层走 CookieManager/RetrofitClient 的既有保存路径。
     * 这次登录**还没落盘**，所以下面的校验必须显式带这份 cookie。
     */
    suspend fun checkLoginQr(key: String): LoginQrStatus = withContext(Dispatchers.IO) {
        val response = RetrofitClient.eapiPostPc(
            QrLoginProtocol.CLIENT_LOGIN_PATH,
            QrLoginProtocol.clientLoginFields(key)
        )
        val body = response.body?.string() ?: return@withContext LoginQrStatus(-1, null)
        val code = runCatching { JSONObject(body).optInt("code", -1) }.getOrDefault(-1)
        if (code == 803) {
            val setCookies = response.headers("Set-Cookie")
            // 日志只记业务码与 Set-Cookie 条数：cookie 内容一律不进日志。
            Log.d("PlaylistApi", "qr803 setCookie=${setCookies.size}")
            return@withContext LoginQrStatus(code, QrLoginProtocol.sessionCookie(setCookies))
        }
        LoginQrStatus(code, null)
    }

    /** 会话校验结果：Invalid = 服务端明确说没登录；Unknown = 网络 / 解析失败，判不了。 */
    enum class SessionCheck { Valid, Invalid, Unknown }

    /**
     * 用刚拿到的 cookie 打一次 `account/get`：`account` 非空才算这次登录真的成立。
     *
     * 轮询回 803 只说明手机上确认了，不等于服务端认这个会话；不校验就会出现「提示登录成功、
     * 进首页还是未登录」。网络失败与响应读不出都归 Unknown（**不能**当成无效），
     * 上层据此区分「重试」与「网络异常」。
     */
    suspend fun validateSession(cookie: String): SessionCheck = withContext(Dispatchers.IO) {
        val response = try {
            RetrofitClient.eapiPost(ACCOUNT_GET_PATH, emptyMap(), cookieOverride = cookie)
        } catch (e: Exception) {
            Log.w("PlaylistApi", "session check request failed: ${e.message}")
            return@withContext SessionCheck.Unknown
        }
        val body = try {
            response.body?.string()
        } catch (e: Exception) {
            null
        } ?: return@withContext SessionCheck.Unknown
        val json = runCatching { JSONObject(body) }.getOrNull() ?: return@withContext SessionCheck.Unknown
        if (json.optJSONObject("account") != null) SessionCheck.Valid else SessionCheck.Invalid
    }

    // ==================== 云端收藏（收藏单曲 / 收藏专辑） ====================

    /**
     * 找到「我喜欢的音乐」（红心歌单）的 playlist id。
     *
     * 该歌单在 /eapi/user/playlist 中作为特殊歌单出现（名称「我喜欢的音乐」/specialType == 5，
     * 普通自建歌单 specialType 为 0）。必须精确命中它——旧实现取「第一个 specialType != 0」，
     * 账号里若有其它特殊歌单会读到错误的歌单，导致收藏页读到的单曲集合与 like 写入的目标不一致，
     * 表现为「收藏后隔天消失」。依次按名称、specialType==5、任意特殊歌单三级兜底。
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
        reportBusinessCode(json.optInt("code", -1))
        val arr = json.optJSONArray("playlist") ?: return@withContext null
        // 名称最可靠：「我喜欢的音乐」不可被用户改名。
        for (i in 0 until arr.length()) {
            val name = arr.getJSONObject(i).optString("name")
            if (name.contains("我喜欢的音乐") || name.contains("喜欢的音乐")) {
                return@withContext arr.getJSONObject(i).optLong("id")
            }
        }
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.optInt("specialType") == 5) return@withContext item.optLong("id")
        }
        // 最后兜底：任意特殊歌单（个别老账号 specialType 取值不同）。
        for (i in 0 until arr.length()) {
            val item = arr.getJSONObject(i)
            if (item.optInt("specialType") != 0) return@withContext item.optLong("id")
        }
        null
    }

    /**
     * 读取「我喜欢的音乐」单曲 ID 的显式结果。
     *
     * 关键区分：`Success(emptyList())` 表示服务端权威确认"收藏为空"；
     * `Failure` 表示网络/HTTP/业务码/结构异常——调用方**绝不能**用 Failure 覆盖本地缓存，
     * 否则一次抖动就会被误判成"云端确实没有收藏"，把用户本地收藏清空。
     */
    sealed interface LikedIdsResult {
        data class Success(val ids: List<Long>) : LikedIdsResult
        data class Failure(val reason: String) : LikedIdsResult
    }

    /**
     * 获取「我喜欢的音乐」全部单曲 ID（红心歌单 trackIds，有序、轻量、不会拉全部详情）。
     * 失败不返回空列表，而是 [LikedIdsResult.Failure]，由调用方决定是否保留本地数据。
     */
    suspend fun getLikedTrackIds(uid: Long): LikedIdsResult = withContext(Dispatchers.IO) {
        val playlistId = try {
            getLikedPlaylistId(uid)
        } catch (e: Exception) {
            return@withContext LikedIdsResult.Failure("getLikedPlaylistId threw: ${e.message}")
        } ?: return@withContext LikedIdsResult.Failure("liked playlist not found")

        val payload = mapOf(
            "id" to playlistId.toString(),
            "n" to "1000",
            "s" to "0"
        )
        try {
            val response = RetrofitClient.eapiPost(PLAYLIST_DETAIL_PATH, payload)
            val body = response.body?.string()
                ?: return@withContext LikedIdsResult.Failure("empty body")
            val json = JSONObject(body)
            val code = json.optInt("code", -1)
            if (code != 200) {
                // 登录失效时这里返回 301：立即把会话转 Expired，界面显示失效横幅。
                reportBusinessCode(code)
                return@withContext LikedIdsResult.Failure("business code=$code")
            }
            val playlistObj = json.optJSONObject("playlist")
                ?: return@withContext LikedIdsResult.Failure("missing playlist object")
            val trackIds = playlistObj.optJSONArray("trackIds")
                ?: return@withContext LikedIdsResult.Failure("missing trackIds")
            LikedIdsResult.Success(
                (0 until trackIds.length()).map { trackIds.getJSONObject(it).optLong("id") }
            )
        } catch (e: Exception) {
            LikedIdsResult.Failure("request threw: ${e.message}")
        }
    }

    /** 按 ID 批量拉取单曲详情（eapi/v3/song/detail），供收藏单曲分页 lazy 加载用。 */
    suspend fun getSongsByIds(ids: List<Long>): List<SongItem> = fetchSongDetails(ids)

    /**
     * 收藏(like=true) / 取消收藏(false) 单曲（写入「我喜欢的音乐」）。
     *
     * 主通道是官方 Android 9.x 的 **xeapi** `/api/v1/radio/like`：
     * `trackId,time,alg,like,rqRefer,songRqRefer,mode,subMode,source`，带易盾 token。
     * `time` 只是试听秒数（官方固定 3）；字段集与官方 9.6.13 反编译实证一致（spec/api/xeapi.md §6）；
     * `checkToken`（同一份易盾 token）由 `RetrofitClient.xeapiPost` 在 antiCheat 请求里补进业务体，
     * 官方两端都把它放进体。
     *
     * 为什么不再走老通道：weapi / eapi 的 `/api/radio/like` 写入的收藏当天在官方与两端都可见、
     * **隔天被服务端回滚**。de193ff / 25f0c39 把 `time` 从 Unix 时间戳改成 3 之后**仍复现**，
     * 所以「time 是诱因」这个旧结论是错的——真正的机制是服务端把非官方指纹的写入判为异常环境，
     * 例行审核时回滚（gmg137/netease-cloud-music-gtk#410 实测到 `524「当前环境异常，已取消喜欢」`）。
     * 调研与对照实验见 `spec/api/xeapi.md` 末节。
     *
     * weapi / eapi **只作回退**：两者都还在老通道上，**同样有被事后回滚的风险**；xeapi 失败时用它们
     * 只是「至少这次点亮了」的兜底，不是长久的修复。
     *
     * 注意：like 写操作历史上曾受网易账号/IP 级风险控制，本账号四种协议变体(eapi 最小/
     * eapi+PC 指纹/eapi+安卓身份/经典 weapi)均被 `-460「检测到您的网络环境存在风险」`或
     * 异常响应拦截而读取全部正常。该注释是既往观测，**不代表当前版本/当前账号的现状**——
     * 以实际响应为准。成功判定见 [isLikeWriteAccepted]：xeapi 通道 200 与 502 都算成功，
     * 其余一律失败；weapi/eapi 老通道从严只认 200。
     *
     * 只返回布尔结果不够：调用方（LibraryManager）需要据此决定是否标记"仅本地/待同步"，
     * 并在成功后再做一次读回校验。这里额外记录脱敏的 HTTP 状态、业务码与耗时，便于诊断；
     * **不记录** Cookie、易盾 token、CSRF token 或完整加密请求体。
     */
    suspend fun likeSong(songId: Long, like: Boolean): Boolean = withContext(Dispatchers.IO) {
        if (isLikeWriteAccepted(tryXeapiLike(songId, like))) return@withContext true
        if (tryWeapiLike(songId, like) == 200) return@withContext true
        tryEapiLike(songId, like) == 200
    }

    /**
     * 收藏写入的业务码判定（纯函数，JVM 单测锁定）。
     *
     * 200 正常成功；502 = 歌曲已在期望状态（spec/api/xeapi.md §6，仅 xeapi 通道背书）。
     * 若把 502 当失败，一次「歌其实已在红心歌单」的收藏会掉进 weapi 老通道，
     * 老通道对这类写入可能触发风控 `524「当前环境异常，已取消喜欢」`——把本来就收藏着的歌
     * 反手撤销（issue #33 里「点 + 号直接取消了喜欢」的实际机制）。weapi/eapi 不放行 502：
     * 老通道语义没有 spec 背书，从严。
     */
    fun isLikeWriteAccepted(code: Int): Boolean = code == 200 || code == 502

    /**
     * xeapi `/api/v1/radio/like`——与官方 Android 9.x 同形（xeapi 加密 + 持久 deviceId + 易盾 token）。
     * 字段集按官方 9.6.13 反编译实证（`NeteaseMusicApiImpl.b7`，dexdump 见 spec/api/xeapi.md §6）：
     * `trackId,time,alg,like,rqRefer,songRqRefer,mode,subMode,source,checkToken`。
     * 官方用 HashMap 组字段，**顺序不参与指纹**；字段是否齐全才参与。
     * `rqRefer/songRqRefer` 是"从哪个播放列表点的红心"的引用链，官方由调用场景传入、
     * 可为空串；`mode/subMode` 是场景名（SIMILAR / aidj / FM 模式），无场景时官方也发空串；
     * `source` 官方固定规则：调用方没给 point 就发 `userfm`。Ncrust 没有这些场景，按官方
     * 空值/兜底形态原样发，不臆造场景名。
     * `checkToken` 由 [RetrofitClient.xeapiPost] 在 antiCheat 请求里补进体末尾。
     */
    private fun tryXeapiLike(songId: Long, like: Boolean): Int {
        val startedAt = System.currentTimeMillis()
        return try {
            val text = RetrofitClient.xeapiPost(
                uri = "/api/v1/radio/like",
                pairs = listOf(
                    "trackId" to songId.toString(),
                    "time" to "3",
                    "alg" to "itembased",
                    "like" to like.toString(),
                    // 以下五个字段官方每次必发（9.6.13 实证）。缺字段本身就是可识别指纹，
                    // 这正是 1.4.0 之前"收藏隔天消失"仍可能复现的残余风险点。
                    "rqRefer" to "",
                    "songRqRefer" to "",
                    "mode" to "",
                    "subMode" to "",
                    "source" to "userfm"
                ),
                antiCheat = true
            )
            val code = JSONObject(text).optInt("code", -1)
            Log.i(
                "PlaylistApi",
                "likeSong(xeapi) id=$songId like=$like code=$code elapsedMs=${System.currentTimeMillis() - startedAt}"
            )
            code
        } catch (e: Throwable) {
            Log.w(
                "PlaylistApi",
                "likeSong(xeapi) req failed id=$songId like=$like " +
                    "elapsedMs=${System.currentTimeMillis() - startedAt}: ${e.message}"
            )
            -1
        }
    }

    /** 回退通道 1：经典 weapi `/api/radio/like`。**老通道，收藏可能被服务端事后回滚**。 */
    private fun tryWeapiLike(songId: Long, like: Boolean): Int {
        return try {
            val payload = JSONObject()
                .put("alg", "itembased")
                .put("trackId", songId)
                .put("like", like)
                .put("time", "3")
                .toString()
            val response = RetrofitClient.weapiPost("/api/radio/like", payload)
            val body = response.body?.string() ?: return -1
            JSONObject(body).optInt("code", -1).also {
                Log.i("PlaylistApi", "likeSong(weapi) id=$songId like=$like code=$it")
            }
        } catch (e: Throwable) {
            Log.w("PlaylistApi", "likeSong(weapi) req failed id=$songId like=$like", e)
            -1
        }
    }

    /** 回退通道 2：eapi「官方 iPhone 指纹」`/eapi/radio/like`。**同样是老通道，可能被事后回滚**。 */
    private fun tryEapiLike(songId: Long, like: Boolean): Int {
        val payload = mapOf(
            "alg" to "itembased",
            "trackId" to songId.toString(),
            "like" to like.toString(),
            "time" to "3",
            "e_r" to "TRUE",
            "csrf_token" to (RetrofitClient.getCsrfToken().orEmpty())
        )
        val startedAt = System.currentTimeMillis()
        val http = try {
            RetrofitClient.eapiPostOfficial("/eapi/radio/like", payload)
        } catch (e: Throwable) {
            Log.w("PlaylistApi", "likeSong(eapi) req failed id=$songId like=$like", e)
            return -1
        }
        val elapsed = System.currentTimeMillis() - startedAt
        val raw = try { http.body?.bytes() } catch (_: Throwable) { null }
        if (raw == null) {
            Log.w("PlaylistApi", "likeSong(eapi) id=$songId like=$like http=${http.code} no body elapsedMs=$elapsed")
            return -1
        }
        val plain = EapiCrypto.decryptResponse(java.util.Base64.getEncoder().encodeToString(raw))
        val jsonText = plain.ifEmpty { String(raw) }
        var code = -1
        var parseOk = false
        try {
            code = JSONObject(jsonText).optInt("code", -1)
            parseOk = true
        } catch (_: Throwable) { /* parseOk stays false */ }
        Log.i(
            "PlaylistApi",
            "likeSong(eapi/client) id=$songId like=$like http=${http.code} " +
                "code=$code parsed=$parseOk elapsedMs=$elapsed"
        )
        return code
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