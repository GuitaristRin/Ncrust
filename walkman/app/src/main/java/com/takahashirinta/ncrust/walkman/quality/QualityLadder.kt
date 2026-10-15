package com.takahashirinta.ncrust.walkman.quality

/**
 * 音质阶梯。规则来自 `spec/fixtures/quality/README.md`，夹具 `spec/fixtures/quality/quality.json`。
 *
 * 全部是纯函数，不碰网络也不碰播放器：W2 的取链与出错降级都要用它，所以判定必须能被夹具
 * 单独锁住 —— 把阶梯逻辑埋进取链的循环里，就没法在不发请求的情况下证明它对了。
 *
 * **W0 的 FLAC 门控**：本机（NW-F885 / Android 4.1）没有 MediaCodec 的 `audio/flac` 解码器，
 * 但 `MediaPlayer` 走的是 Stagefright 的解析路径，**可能**能直接播 FLAC。这两条路不一样，
 * 而 W0 还没做登录（FLAC 档要 VIP），所以本阶段**不调用** [levelsFor] 的 `supportsFlac = false`
 * 分支，取链时按 `supportsFlac = true` 把 FLAC 档留在阶梯里。门控函数本身照规格实现并由夹具
 * 锁住，W4 登录后实测再决定取链时传哪个值。
 */
object QualityLadder {

    /** 偏好索引顺序（0..6），与 `spec/fixtures/quality/README.md` 一致。 */
    val PREFERENCES: List<String> =
        listOf("standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "dolby")

    /** 需要 FLAC 解码能力的档位。`dolby` 不在其中：它走 mp4（EAC3）容器。 */
    private val FLAC_TIERS = setOf("lossless", "hires", "jyeffect")

    /** 出错时降一级的顺序。**注意与取链的降级序列不同**：这里 `jyeffect` 排在 `hires` 前面。 */
    private val RETRY_ORDER = listOf("dolby", "jyeffect", "hires", "lossless", "exhigh", "higher", "standard")

    /**
     * 请求档位 → 依次尝试的档位序列。
     *
     * 阶梯外的档位（服务端可能返回 `sky` 之类）按「该档位 + lossless 起的标准序列」处理，
     * 而不是当成 standard —— 服务端说了一个我们不认识的档位，不代表它给的是最低档。
     */
    fun levelsFor(level: String, supportsFlac: Boolean): List<String> {
        val base = when (level) {
            "dolby" -> listOf("dolby", "hires", "lossless", "exhigh", "higher", "standard")
            "jyeffect" -> listOf("jyeffect", "lossless", "exhigh", "higher", "standard")
            "hires" -> listOf("hires", "lossless", "exhigh", "higher", "standard")
            "lossless" -> listOf("lossless", "exhigh", "higher", "standard")
            "exhigh" -> listOf("exhigh", "higher", "standard")
            "higher" -> listOf("higher", "standard")
            "standard" -> listOf("standard")
            else -> listOf(level, "lossless", "exhigh", "higher", "standard")
        }
        return if (supportsFlac) base else base.filter { it !in FLAC_TIERS }
    }

    /**
     * 播放解码失败时降一级；已在最低档返回 null（由上层跳歌）。
     *
     * 阶梯外的档位从 `lossless` 起降 —— 直接跳歌会白白丢掉还能播的档位。
     */
    fun retryNext(level: String): String? {
        val index = RETRY_ORDER.indexOf(level)
        if (index < 0) return "lossless"
        return RETRY_ORDER.getOrNull(index + 1)
    }

    /** 偏好索引 → 档位。越界兜底为 `lossless`（与 Android `getOrElse` 一致）。 */
    fun levelForPreference(index: Int): String =
        PREFERENCES.getOrElse(index) { "lossless" }

    /**
     * 档位在 7 级阶梯里的位置；阶梯外返回 -1。
     *
     * 权益判定要用它比较「请求档位」与「实际档位」，未知档位一律判为不可比 —— 拿字符串长度或
     * 字典序去比会得出荒谬的结论。
     */
    fun rankOf(level: String?): Int = if (level == null) -1 else PREFERENCES.indexOf(level)
}
