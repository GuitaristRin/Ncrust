package com.takahashirinta.ncrust.walkman.api

import com.takahashirinta.ncrust.walkman.quality.QualityLadder

/**
 * 单曲播放结果分类。规则全文 `spec/api/entitlement.md`，夹具
 * `spec/fixtures/entitlement/playback.json`。
 *
 * 做成纯函数（不碰网络、不碰会话对象）是为了能被夹具逐条锁住：这套判定存在的意义是
 * 「把网易的限制讲清楚，而不是让它看起来像客户端的 bug」，判错方向（把试听说成完整、
 * 把无版权说成网络错误）比不判还糟。
 *
 * 判定顺序不能换：先看 `url` 有没有、再看有没有 `freeTrialInfo`、最后才看 `privilege`。
 * 试听项**也有** `url`，只按 `url` 判会把 45 秒片段当成完整可播。
 */
object Entitlement {

    enum class Kind { TRIAL, FULL, UNAVAILABLE }

    /** 不可播 / 受限的原因。`UNKNOWN` 是"没有信号可判"，不是"其它"。 */
    enum class Reason { LOGIN, VIP, ALBUM, COPYRIGHT, UNKNOWN }

    data class Verdict(
        val kind: Kind,
        val trialSeconds: Int? = null,
        val reason: Reason? = null,
        val capped: Boolean = false,
        val cappedTo: String? = null,
        val cappedReason: Reason? = null
    )

    /**
     * @param item `url/v1` 返回的 `data[i]`。
     * @param requestedLevel 本次请求的档位（不是服务端返回的 `level`）。
     * @param privilege 歌曲详情的 `privilege`；拿不到时传 null，此时降级用 `item.fee` 判因。
     * @param sessionValid 会话是否 `Valid`（`spec/api/entitlement.md` 的三态模型）。
     */
    fun classifyPlayback(
        item: SongUrlItem,
        requestedLevel: String,
        privilege: Privilege?,
        sessionValid: Boolean
    ): Verdict {
        val hasUrl = !item.url.isNullOrEmpty()

        // 1) 有 url 但有 freeTrialInfo → 试听片段。fee 4 是数字专辑（要买），其余归 VIP。
        //    原因优先级：专辑 > 未登录 > 需要 VIP —— 夹具 album-trial（未登录 + fee 4）
        //    期望 album，所以"未登录"不能盖过"要买专辑"。
        val trial = item.freeTrialInfo
        if (hasUrl && trial != null) {
            val reason = when {
                item.fee == 4 -> Reason.ALBUM
                !sessionValid -> Reason.LOGIN
                else -> Reason.VIP
            }
            return Verdict(kind = Kind.TRIAL, trialSeconds = trial.end - trial.start, reason = reason)
        }

        // 2) 有 url 且非试听 → 完整音源。实际档位低于请求档位时算被限（capped）。
        //    任一侧是阶梯外档位（如 sky）就不可比，判为不 capped —— 猜一个顺序比不判更危险。
        if (hasUrl) {
            val requested = QualityLadder.rankOf(requestedLevel)
            val actual = QualityLadder.rankOf(item.level)
            val capped = requested >= 0 && actual >= 0 && actual < requested
            return Verdict(
                kind = Kind.FULL,
                capped = capped,
                cappedTo = if (capped) item.level else null,
                cappedReason = if (!capped) null else if (sessionValid) Reason.VIP else Reason.LOGIN
            )
        }

        // 3) 没有 url → 不可播。有详情 privilege 时用它的 fee/st（更权威），没有才退回 item.fee。
        val fee = privilege?.fee ?: item.fee
        val reason = when {
            (privilege?.st ?: 0) < 0 -> Reason.COPYRIGHT
            fee == 4 -> Reason.ALBUM
            fee == 1 -> if (sessionValid) Reason.VIP else Reason.LOGIN
            else -> Reason.UNKNOWN
        }
        return Verdict(kind = Kind.UNAVAILABLE, reason = reason)
    }
}
