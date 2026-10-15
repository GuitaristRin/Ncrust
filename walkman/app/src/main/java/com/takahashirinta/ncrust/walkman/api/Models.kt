package com.takahashirinta.ncrust.walkman.api

/**
 * 接口层的数据模型。只有 W0 需要的字段 —— 不做"照抄服务端 JSON"的完整映射：
 * 每个没用到的字段都是一处日后会静默过期的地方。
 */

/** 歌曲详情的 `privileges[]`。`st` 为负表示无版权（`spec/api/entitlement.md`）。 */
data class Privilege(
    val id: Long,
    val st: Int,
    val pl: Long,
    val fee: Int
)

/** `song/enhance/player/url/v1` 的 `freeTrialInfo`：试听片段，秒为单位。 */
data class FreeTrialInfo(val start: Int, val end: Int)

/** `song/enhance/player/url/v1` 的 `data[i]`。 */
data class SongUrlItem(
    val id: Long,
    val url: String?,
    val code: Int,
    val level: String?,
    val fee: Int,
    val freeTrialInfo: FreeTrialInfo?,
    /** 实际码率（bps）。冒烟界面要显示它，用来确认"服务端真的给了 standard 档"而不只是标签。 */
    val br: Long = 0L
)

/** 歌曲详情（`/api/v3/song/detail` 与 `/eapi/v3/song/detail` 同形）。 */
data class Song(
    val id: Long,
    val name: String,
    val artists: String,
    val album: String,
    val durationMs: Long,
    val coverUrl: String?,
    val privilege: Privilege?
)

/** 歌词。`translation` 是 `tlyric`，没有译文时为 null（不是空串）。 */
data class Lyric(val lrc: String, val translation: String?)
