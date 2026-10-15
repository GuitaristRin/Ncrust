package com.takahashirinta.ncrust.walkman.api

import com.takahashirinta.ncrust.walkman.json.Json
import com.takahashirinta.ncrust.walkman.net.Requests
import com.takahashirinta.ncrust.walkman.quality.QualityLadder

/**
 * W0 需要的接口。全部**同步**（调用方自己在后台线程跑）—— 本工程不用协程（`TASKBOOK.md` §1
 * 说"协程可选：设备上验证可用再引入"），而 W0 只有冒烟界面一个调用方。
 *
 * 失败一律抛 [ApiException]，消息里带上 HTTP 码 / 业务码 / 响应片段：W0 在 4.1 上排查问题时，
 * 一句"取链失败"没有任何用处，得能看出是 TLS、是业务码还是解析。
 */
object NcmApi {

    private const val SONG_DETAIL_PATH = "/eapi/v3/song/detail"
    private const val SONG_URL_PATH = "/eapi/song/enhance/player/url/v1"
    private const val LYRIC_PATH = "/api/song/lyric"
    private const val DAILY_RECOMMEND_PATH = "/eapi/v2/discovery/recommend/songs"
    private const val ACCOUNT_GET_PATH = "/eapi/w/nuser/account/get"
    private const val USER_PLAYLIST_PATH = "/eapi/user/playlist"
    private const val PLAYLIST_DETAIL_PATH = "/eapi/v6/playlist/detail"

    /** 红心歌单在 `/eapi/user/playlist` 里的标记（普通自建歌单是 0）。 */
    private const val SPECIAL_TYPE_LIKED = 5

    class ApiException(message: String, val code: Int? = null) : Exception(message)

    /** 取链结果。`url` 为空表示阶梯走完了都没有可播地址。 */
    data class UrlResult(
        val requestedLevel: String,
        /** 实际拿到的档位；全失败时为 null。 */
        val resolvedLevel: String?,
        val url: String?,
        val item: SongUrlItem?,
        val verdict: Entitlement.Verdict?,
        val triedLevels: List<String>,
        val lastCode: Int?,
        val tlsVersion: String?
    )

    // ------------------------------------------------------------------ 歌曲详情

    /** `/eapi/v3/song/detail`，同时解析 `privileges[]`（权益判定要用它的 `st` / `fee`）。 */
    fun songDetail(ids: List<Long>): List<Song> {
        require(ids.isNotEmpty()) { "songDetail 需要至少一个 id" }
        val c = ids.joinToString(prefix = "[", postfix = "]") { "{\"id\":$it}" }
        val payload = "{\"c\":" + Json.quote(c) + "}"
        val root = callEapi(SONG_DETAIL_PATH, payload)

        val privileges = root.arrOrNull("privileges")?.items.orEmpty()
            .mapNotNull { it.asObjOrNull }
            .associate { it.longOrNull("id") to it.toPrivilege() }

        return root.arrOrNull("songs")?.items.orEmpty().mapNotNull { element ->
            val song = element.asObjOrNull ?: return@mapNotNull null
            val id = song.longOrNull("id") ?: return@mapNotNull null
            Song(
                id = id,
                name = song.strOrNull("name").orEmpty(),
                artists = song.arrOrNull("ar")?.items.orEmpty()
                    .mapNotNull { it.asObjOrNull?.strOrNull("name") }
                    .joinToString("/"),
                album = song.objOrNull("al")?.strOrNull("name").orEmpty(),
                durationMs = song.longOrNull("dt") ?: 0L,
                coverUrl = song.objOrNull("al")?.strOrNull("picUrl"),
                privilege = privileges[id]
            )
        }
    }

    // ------------------------------------------------------------------ 取链

    /**
     * `/eapi/song/enhance/player/url/v1`，按 [QualityLadder] 的阶梯逐档降级，返回第一个有 url 的档位。
     *
     * **FLAC 门控在本阶段被刻意跳过**：`supportsFlac` 传的是常量 `true`，也就是把
     * `lossless` / `hires` / `jyeffect` 三档留在阶梯里。原因是本机的两条解码路不一样 ——
     * `MediaCodec` 确实没有 `audio/flac` 解码器（Android 4.1），但 `MediaPlayer` 走的是
     * Stagefright 的解析路径，**可能**能直接播 FLAC。W0 还没登录（FLAC 档要 VIP），
     * 所以无法实测；W4 登录后实测再决定这里传什么。
     * [QualityLadder.levelsFor] 的门控分支本身已由 `quality.json` 夹具锁住，随时可以启用。
     *
     * @param sessionValid 会话是否 Valid，只影响权益判定的归因（`login` / `vip`）。
     */
    fun songUrl(
        songId: Long,
        level: String,
        privilege: Privilege? = null,
        sessionValid: Boolean = false
    ): UrlResult = resolveSongUrl(songId, level, privilege, sessionValid) { tryLevel ->
        fetchSongUrl(songId, tryLevel)
    }

    /** 将网络边界注入，测试真实的降级与权益组合，不访问外网。 */
    internal fun resolveSongUrl(
        songId: Long,
        level: String,
        privilege: Privilege?,
        sessionValid: Boolean,
        fetch: (String) -> Requests.Raw
    ): UrlResult {
        val levels = QualityLadder.levelsFor(level, supportsFlac = true)
        var lastCode: Int? = null
        var lastTls: String? = null
        var lastItem: SongUrlItem? = null

        for (tryLevel in levels) {
            val response = fetch(tryLevel)
            lastTls = response.tlsVersion
            if (!response.isHttpOk) {
                throw ApiException("取链 HTTP ${response.httpCode}（level=$tryLevel）")
            }
            val root = Json.parseOrNull(response.text)
                ?: throw ApiException("取链响应不是 JSON（level=$tryLevel）：${response.text.take(200)}")
            lastCode = root.intOrNull("code")
                ?: throw ApiException("取链响应缺业务码（level=$tryLevel）")
            if (lastCode == 301) throw ApiException("登录已失效", code = 301)

            val item = root.arrOrNull("data")?.items.orEmpty()
                .mapNotNull { it.asObjOrNull }
                .firstOrNull { it.longOrNull("id") == songId }
                ?.toUrlItem()
            if (item != null) lastItem = item

            // code != 200 表示这一档没有资源（404 = 无版权 / 无权益），继续降级。
            if (lastCode != 200 || item == null || item.code != 200 || item.url.isNullOrEmpty()) continue

            return UrlResult(
                requestedLevel = level,
                resolvedLevel = item.level ?: tryLevel,
                url = item.url,
                item = item,
                verdict = Entitlement.classifyPlayback(item, level, privilege, sessionValid),
                triedLevels = levels.take(levels.indexOf(tryLevel) + 1),
                lastCode = lastCode,
                tlsVersion = lastTls
            )
        }

        // 保留不可播数据供上层区分版权、专辑、VIP、未登录；不能让降级吃掉权益信息。
        val unavailable = (lastItem ?: SongUrlItem(songId, null, lastCode ?: 0, null, 0, null))
            .copy(url = null)
        return UrlResult(
            requestedLevel = level,
            resolvedLevel = null,
            url = null,
            item = unavailable,
            verdict = Entitlement.classifyPlayback(unavailable, level, privilege, sessionValid),
            triedLevels = levels,
            lastCode = lastCode,
            tlsVersion = lastTls
        )
    }

    private fun fetchSongUrl(songId: Long, level: String): Requests.Raw {
        val requestId = (20_000_000..30_000_000).random()
        val header = "{\"os\":\"pc\",\"appver\":\"\",\"osver\":\"\",\"deviceId\":\"pyncm!\",\"requestId\":\"$requestId\"}"
        val encodeType = if (level == "dolby") "mp4" else "flac"
        val payload = "{\"ids\":\"[$songId]\",\"level\":${Json.quote(level)}," +
            "\"header\":" + Json.quote(header) + ",\"encodeType\":\"$encodeType\"}"
        return Requests.eapiPost(SONG_URL_PATH, payload, useInterface = true)
    }

    // ------------------------------------------------------------------ 歌词

    /** `/api/song/lyric`（REST，明文表单，打 `music.163.com`）。 */
    fun lyric(songId: Long): Lyric {
        val response = Requests.postForm(
            url = Requests.BASE_URL + LYRIC_PATH,
            form = listOf(
                "id" to songId.toString(),
                "cp" to "false",
                "tv" to "-1",
                "lv" to "-1",
                "rv" to "0",
                "kv" to "0",
                "yv" to "0",
                "ytv" to "0",
                "yrv" to "0"
            ),
            referer = "https://music.163.com/"
        )
        if (!response.isHttpOk) throw ApiException("歌词 HTTP ${response.httpCode}")
        val root = Json.parseOrNull(response.text)
            ?: throw ApiException("歌词响应不是 JSON：${response.text.take(200)}")
        val code = root.intOrNull("code") ?: throw ApiException("歌词响应缺业务码")
        if (code != 200) throw ApiException("歌词业务码 $code", code)
        val lrc = root.objOrNull("lrc")?.strOrNull("lyric").orEmpty()
        val translation = root.objOrNull("tlyric")?.strOrNull("lyric").orEmpty().takeIf { it.isNotEmpty() }
        return Lyric(lrc = lrc, translation = translation)
    }

    // ------------------------------------------------------------------ 需登录

    /**
     * `/eapi/v2/discovery/recommend/songs`（每日推荐）。
     *
     * **需要登录**：匿名时服务端会回一份"非个性化"的通用列表（`spec/api/entitlement.md`），
     * 而不是报错 —— 那种情况下把列表当"每日推荐"展示是在骗用户，所以这里**主动判掉**：
     * 会话无效直接抛 [ApiException]，由上层决定是否提示"登录后为你推荐"。
     * W1 接上会话三态后，这里的 `sessionValid` 改成从会话单例取。
     */
    fun dailyRecommend(sessionValid: Boolean): List<Song> {
        if (!sessionValid) throw ApiException("每日推荐需要登录（匿名返回的是通用列表，不是个性化推荐）")
        val root = callEapi(DAILY_RECOMMEND_PATH, "{}")
        val data = root.objOrNull("data") ?: throw ApiException("每日推荐响应缺 data")
        return data.arrOrNull("dailySongs")?.items.orEmpty().mapNotNull { it.asSong() }
    }

    /**
     * 「我喜欢的音乐」的 id 列表。
     *
     * 三步：`account/get` 拿 uid → `user/playlist` 找 `specialType == 5` 的红心歌单 →
     * `v6/playlist/detail` 取 `trackIds`（`trackIds` 是全量的，`tracks` 不是）。
     * 必须精确命中 `specialType == 5`：取"第一个非 0 特殊歌单"会命中别的歌单，
     * 表现为"收藏后隔天消失"（Android 侧踩过这个坑）。
     *
     * **需要登录**，匿名时抛 [ApiException]（`account/get` 会回 `account: null`）。
     */
    fun likedTrackIds(): List<Long> {
        val account = callEapi(ACCOUNT_GET_PATH, "{}")
        val uid = account.objOrNull("account")?.longOrNull("id")
            ?: account.objOrNull("profile")?.longOrNull("id")
            ?: throw ApiException("我喜欢的音乐需要登录（account/get 没有返回账号）")

        val playlists = callEapi(
            USER_PLAYLIST_PATH,
            "{\"uid\":\"$uid\",\"limit\":\"1000\",\"offset\":\"0\",\"includeVideo\":\"false\"}"
        )
        val likedId = playlists.arrOrNull("playlist")?.items.orEmpty()
            .mapNotNull { it.asObjOrNull }
            .firstOrNull { it.intOrNull("specialType") == SPECIAL_TYPE_LIKED }
            ?.longOrNull("id")
            ?: throw ApiException("没有找到红心歌单（specialType == $SPECIAL_TYPE_LIKED）")

        val detail = callEapi(PLAYLIST_DETAIL_PATH, "{\"id\":\"$likedId\",\"n\":\"1000\",\"s\":\"0\"}")
        val trackIds = detail.objOrNull("playlist")?.arrOrNull("trackIds")
            ?: throw ApiException("歌单详情缺 trackIds")
        return trackIds.items.mapNotNull { it.asObjOrNull?.longOrNull("id") }
    }

    // ------------------------------------------------------------------ 内部

    /** eapi 调用 + HTTP/业务码检查 + JSON 解析。 */
    private fun callEapi(path: String, payloadJson: String): Json {
        val response = Requests.eapiPost(path, payloadJson)
        if (!response.isHttpOk) throw ApiException("$path HTTP ${response.httpCode}")
        val root = Json.parseOrNull(response.text)
            ?: throw ApiException("$path 响应不是 JSON：${response.text.take(200)}")
        val code = root.intOrNull("code")
            ?: throw ApiException("$path 响应缺业务码")
        if (code != 200) throw ApiException("$path 业务码 $code", code)
        return root
    }

    private fun Json.asSong(): Song? {
        val obj = asObjOrNull ?: return null
        val id = obj.longOrNull("id") ?: return null
        return Song(
            id = id,
            name = obj.strOrNull("name").orEmpty(),
            artists = obj.arrOrNull("ar")?.items.orEmpty()
                .mapNotNull { it.asObjOrNull?.strOrNull("name") }
                .joinToString("/"),
            album = obj.objOrNull("al")?.strOrNull("name").orEmpty(),
            durationMs = obj.longOrNull("dt") ?: 0L,
            coverUrl = obj.objOrNull("al")?.strOrNull("picUrl"),
            privilege = null
        )
    }

    private fun Json.toPrivilege(): Privilege = Privilege(
        id = longOrNull("id") ?: 0L,
        st = intOrNull("st") ?: 0,
        pl = longOrNull("pl") ?: 0L,
        fee = intOrNull("fee") ?: 0
    )

    private fun Json.toUrlItem(): SongUrlItem = SongUrlItem(
        id = longOrNull("id") ?: 0L,
        url = strOrNull("url"),
        code = intOrNull("code") ?: 0,
        level = strOrNull("level"),
        fee = intOrNull("fee") ?: 0,
        freeTrialInfo = objOrNull("freeTrialInfo")?.let {
            FreeTrialInfo(start = it.intOrNull("start") ?: 0, end = it.intOrNull("end") ?: 0)
        },
        br = longOrNull("br") ?: 0L
    )

    /** 供 W1 复用：当前登录 uid；匿名返回 null。 */
    fun currentUserIdOrNull(): Long? = runCatching {
        callEapi(ACCOUNT_GET_PATH, "{}").objOrNull("account")?.longOrNull("id")
    }.getOrNull()
}
