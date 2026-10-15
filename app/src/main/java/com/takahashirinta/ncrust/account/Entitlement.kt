package com.takahashirinta.ncrust.account

import com.google.gson.annotations.SerializedName

/**
 * 单曲播放权益判定（纯函数）。
 *
 * 规格：`spec/api/entitlement.md`；答案：`spec/fixtures/entitlement/playback.json`。
 * 背景：网易对未登录 / 非会员**不报错**，而是返回 200 + 45 秒试听 URL、或把音质悄悄降到
 * `privilege.pl`、或 404 + `url: null`。客户端照单全收，用户看到的就是「放到一半断了 /
 * 音质设置不生效 / 点了没反应」，自然归咎于 Ncrust 本身。
 *
 * 这里不碰 Android 与 org.json：夹具用 Gson 解析后直接喂进来，与线上链路（同样是 Gson
 * 解析响应体）共用同一套字段映射——单测里 org.json 是空桩，走 JSONObject 的判定函数没法测。
 */

/** 7 级音质阶梯（偏好索引顺序），与 `spec/fixtures/quality/README.md` 一致。 */
object QualityLadder {
    val LEVELS = listOf("standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "dolby")

    /** 阶梯内档位的索引；阶梯外（服务端可能返回 `sky` 等）返回 -1，表示「不可比较」。 */
    fun indexOf(level: String?): Int = if (level == null) -1 else LEVELS.indexOf(level)
}

/**
 * 判定结果的原因。
 *
 * trial / capped 只用 `VIP` `ALBUM` `LOGIN`，unavailable 用全集（含 `COPYRIGHT` `UNKNOWN`）——
 * 用一个枚举是为了让界面只写一处「原因 → 文案」映射，各分支的合法取值由 [classifyPlayback]
 * 保证。
 */
enum class EntitlementReason(val wire: String) {
    VIP("vip"),
    ALBUM("album"),
    LOGIN("login"),
    COPYRIGHT("copyright"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(value: String?): EntitlementReason? =
            entries.firstOrNull { it.wire == value }
    }
}

/**
 * 一次取链的判定结果。
 *
 * - [Full] 可完整播放；[Full.capped] 为真时实际拿到的是更低的档位（账号权限不足，
 *   不是设备原因——FLAC 门控造成的降级在调用前就已发生，不算 capped）。
 * - [Trial] 只能播 [Trial.seconds] 秒试听片段。
 * - [Unavailable] 所有档位都拿不到 URL，必须跳歌（绝不回退 outer/url 兜底）。
 */
sealed interface PlaybackEntitlement {
    data class Full(
        val capped: Boolean,
        val cappedTo: String? = null,
        val cappedReason: EntitlementReason? = null
    ) : PlaybackEntitlement

    data class Trial(
        val seconds: Int,
        val reason: EntitlementReason
    ) : PlaybackEntitlement

    data class Unavailable(
        val reason: EntitlementReason
    ) : PlaybackEntitlement
}

/** `song/enhance/player/url/v1` 的 `data[i]`，只保留判定用得上的字段。 */
data class PlaybackItem(
    @SerializedName("url") val url: String? = null,
    @SerializedName("code") val code: Int? = null,
    @SerializedName("level") val level: String? = null,
    @SerializedName("fee") val fee: Int? = null,
    @SerializedName("freeTrialInfo") val freeTrialInfo: FreeTrialInfo? = null,
    @SerializedName("freeTrialPrivilege") val freeTrialPrivilege: FreeTrialPrivilege? = null
)

data class FreeTrialInfo(
    @SerializedName("start") val start: Int? = null,
    @SerializedName("end") val end: Int? = null
)

/**
 * 试听限制的细节。判定规则本身不用它（规则只看 `url` / `level` / `fee` / `freeTrialInfo`
 * 与歌曲详情的 `privilege.st`），带出来是为了日志与将来细文案（如 `cannotListenReason`）。
 */
data class FreeTrialPrivilege(
    @SerializedName("resConsumable") val resConsumable: Boolean? = null,
    @SerializedName("userConsumable") val userConsumable: Boolean? = null,
    @SerializedName("cannotListenReason") val cannotListenReason: Int? = null,
    @SerializedName("freeLimitTagType") val freeLimitTagType: String? = null
)

/** 歌曲详情里的 `privilege`（`song/detail` / `playlist/detail` 的 `privileges[]`）。 */
data class SongPrivilege(
    /** `st < 0` = 无版权 / 已下架。 */
    @SerializedName("st") val st: Int? = null,
    /** 账号实际可播的最高码率档（`pl`），单独出现时不足以判定，仅作诊断。 */
    @SerializedName("pl") val pl: Long? = null,
    @SerializedName("fee") val fee: Int? = null
)

/**
 * 判定单曲播放结果（`spec/api/entitlement.md` §单曲播放结果分类，三端一致）。
 *
 * @param requestedLevel 客户端**实际尝试的第一档**。调用方必须传 FLAC 门控之后的第一档，
 *   否则设备解不了 FLAC 造成的降级会被误判成「账号权限不足」。
 * @param sessionValid 会话是否 `Valid`（`Unknown` 不算 Valid）。它只影响原因文案，不影响能否播。
 * @param item 最后一次取链响应项；网络全失败时为 null，只能给 `unknown`。
 * @param privilege 歌曲详情里的 `privilege`，没有就传 null（规则已考虑）。
 */
fun classifyPlayback(
    requestedLevel: String,
    sessionValid: Boolean,
    item: PlaybackItem?,
    privilege: SongPrivilege?
): PlaybackEntitlement {
    // 一次响应都没有（全档位网络失败）：没有任何依据区分版权/会员，不编原因。
    if (item == null) return PlaybackEntitlement.Unavailable(EntitlementReason.UNKNOWN)

    val url = item.url
    // fee 优先取歌曲详情的（更权威），没有详情时退回取链响应项里的。
    val fee = privilege?.fee ?: item.fee

    if (!url.isNullOrEmpty()) {
        val trial = item.freeTrialInfo
        if (trial != null) {
            // 有 url 但带 freeTrialInfo = 官方给的试听片段（实测 VIP 歌对非会员返回 45 秒）。
            val seconds = ((trial.end ?: 0) - (trial.start ?: 0)).coerceAtLeast(0)
            val reason = when {
                fee == 4 -> EntitlementReason.ALBUM
                !sessionValid -> EntitlementReason.LOGIN
                else -> EntitlementReason.VIP
            }
            return PlaybackEntitlement.Trial(seconds, reason)
        }
        val requestIdx = QualityLadder.indexOf(requestedLevel)
        val actualIdx = QualityLadder.indexOf(item.level)
        // 只有两端都在 7 级阶梯内才可比；阶梯外档位（如 sky）不参与比较、不算 capped。
        val capped = requestIdx >= 0 && actualIdx >= 0 && actualIdx < requestIdx
        return PlaybackEntitlement.Full(
            capped = capped,
            cappedTo = if (capped) item.level else null,
            cappedReason = if (capped) {
                if (sessionValid) EntitlementReason.VIP else EntitlementReason.LOGIN
            } else null
        )
    }

    val reason = when {
        (privilege?.st ?: 0) < 0 -> EntitlementReason.COPYRIGHT
        fee == 4 -> EntitlementReason.ALBUM
        fee == 1 -> if (sessionValid) EntitlementReason.VIP else EntitlementReason.LOGIN
        else -> EntitlementReason.UNKNOWN
    }
    return PlaybackEntitlement.Unavailable(reason)
}
