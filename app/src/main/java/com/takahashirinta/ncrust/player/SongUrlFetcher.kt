package com.takahashirinta.ncrust.player

import android.media.MediaCodecList
import android.os.Build
import android.util.Log
import com.takahashirinta.ncrust.account.FreeTrialInfo
import com.takahashirinta.ncrust.account.FreeTrialPrivilege
import com.takahashirinta.ncrust.account.PlaybackItem
import com.takahashirinta.ncrust.account.SongPrivilege
import com.takahashirinta.ncrust.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 一次取链的结果。
 *
 * 不能再只返回 url：判定「45 秒试听 / 音质被账号封顶 / 无版权」需要
 * `code` `level` `fee` `freeTrialInfo` `freeTrialPrivilege` 与歌曲详情的 `privilege`
 * （规则见 `spec/api/entitlement.md` §单曲播放结果分类）。这些字段以前拿到 url 就丢了，
 * 上层只看得见"有个 url"，于是 VIP 歌的试听片段被当成完整歌播放、放到 45 秒"自己断了"。
 *
 * - [url] 为 null = 所有档位都没拿到可播 URL。此时 [item] 仍是**最后一次**的响应项，
 *   供上层判定 unavailable 的原因（绝不用 `song/media/outer/url` 兜底，见 AGENTS.md）。
 * - [requestedLevel] 是 FLAC 门控之后实际尝试的第一档。判定 capped 必须用它：
 *   设备解不了 FLAC 造成的降级是设备原因，不算账号降级。
 */
data class SongUrlResult(
    val url: String?,
    val actualLevel: String?,
    val requestedLevel: String,
    val item: PlaybackItem?,
    val privilege: SongPrivilege?
)

object SongUrlFetcher {
    private const val TAG = "SongUrlFetcher"
    private const val SONG_URL_PATH = "/eapi/song/enhance/player/url/v1"

    // FLAC 只能走 MediaCodec(本工程未带 FFmpeg 软解)。API 27 起才有 FLAC 解码器,
    // 更老的系统(以及个别缺 FLAC 解码器的 ROM)拿到 flac URL 也播不出声,
    // 直接在取链阶段跳过这些档位,落到 mp3 档,避免"有进度没声音"。
    private val FLAC_TIERS = setOf("lossless", "hires", "jyeffect")
    private val deviceCanDecodeFlac: Boolean by lazy {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            false
        } else {
            runCatching {
                MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                    !info.isEncoder && info.supportedTypes.any { it.equals("audio/flac", ignoreCase = true) }
                }
            }.getOrDefault(false)
        }
    }

    /**
     * 取链。
     *
     * 返回的 [SongUrlResult.url] 为 null 表示所有档位都不可播；调用方必须跳歌而不是播放坏链接。
     *
     * @param privilege 歌曲详情里的 `privilege`（`SongItem.privilege`），没有就传 null。
     */
    suspend fun fetch(
        songId: Long,
        level: String = "lossless",
        privilege: SongPrivilege? = null
    ): SongUrlResult = withContext(Dispatchers.IO) {
        // Try the requested level first, then fall back down the quality ladder.
        val fallbackLevels = when (level) {
            "dolby"    -> listOf("dolby", "hires", "lossless", "exhigh", "higher", "standard")
            "jyeffect" -> listOf("jyeffect", "lossless", "exhigh", "higher", "standard")
            "hires"    -> listOf("hires", "lossless", "exhigh", "higher", "standard")
            "lossless" -> listOf("lossless", "exhigh", "higher", "standard")
            "exhigh"   -> listOf("exhigh", "higher", "standard")
            "higher"   -> listOf("higher", "standard")
            "standard" -> listOf("standard")
            else       -> listOf(level, "lossless", "exhigh", "higher", "standard")
        }
        // 门控之后真正会发出去的第一档。
        val requestedLevel = fallbackLevels.firstOrNull { it !in FLAC_TIERS || deviceCanDecodeFlac }
            ?: level
        // 最后一次响应项：所有档位都拿不到 url 时，它是判定 unavailable 原因的唯一依据。
        var lastItem: PlaybackItem? = null

        for (tryLevel in fallbackLevels) {
            // 设备解不了 FLAC 时,flac 档位取来也是无声,直接跳到 mp3 档。
            if (tryLevel in FLAC_TIERS && !deviceCanDecodeFlac) continue
            try {
                val payload = buildPayload(songId, tryLevel)
                val response = RetrofitClient.eapiPost(SONG_URL_PATH, payload, useInterface = true)
                val body = response.body?.string() ?: continue
                // 不打印响应体：里面是带签名的音频 URL 与 authSecret，且整段格式化在弱机上也不便宜。
                Log.d(TAG, "eapi response ($tryLevel): ${body.length} chars")
                val json = JSONObject(body)
                val data = json.optJSONArray("data") ?: continue
                if (data.length() > 0) {
                    val obj = data.getJSONObject(0)
                    val item = parseItem(obj)
                    lastItem = item
                    // code != 200 means the level is unavailable (404 = no resource / not entitled).
                    // There is no point accepting such an entry, so advance down the ladder.
                    if ((item.code ?: 200) != 200) continue
                    val url = item.url
                    val actualLevel = item.level ?: tryLevel
                    // type/br 用于诊断:type 是实际容器(mp3/flac/mp4),br 是码率,
                    // 出现"有进度没声音"时靠这两项判断拿到的到底是不是预期的文件。
                    val type = obj.optString("type", "")
                    val br = obj.optLong("br", 0L)
                    if (!url.isNullOrEmpty()) {
                        Log.d(
                            TAG,
                            "got url: len=${url.length} actualLevel: $actualLevel type: $type br: $br " +
                                "requested: $level trial=${item.freeTrialInfo != null}"
                        )
                        return@withContext SongUrlResult(
                            url = url,
                            actualLevel = actualLevel,
                            requestedLevel = requestedLevel,
                            item = item,
                            privilege = privilege
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fetch failed for level=$tryLevel", e)
            }
        }

        // 所有档位都取不到可播放的 URL。绝不要用 https://music.163.com/song/media/outer/url?id=X.mp3
        // 兜底 —— 那个旧端点对无版权/需会员的歌曲返回 302→404 的 HTML 页面，ExoPlayer 拿到非音频流
        // 会无限缓冲（"卡住"）。这里返回 url=null，让上层按判定结果提示原因并跳歌。
        Log.e(TAG, "no playable url for songId=$songId at any level (lastItem=${lastItem?.code})")
        SongUrlResult(
            url = null,
            actualLevel = null,
            requestedLevel = requestedLevel,
            item = lastItem,
            privilege = privilege
        )
    }

    /**
     * `data[0]` → 判定模型。只取判定用得上的字段，其余（size/br/type…）留给日志。
     *
     * 字符串字段必须走 [optStringOrNull]：无版权响应里是 `"url": null`，而 `optString`
     * 对 JSON null 的处理在不同 org.json 实现下并不一致（有的回退默认值，有的返回字面量
     * "null"）。一旦读成 "null"，判定就把"没有 url"看成"有 url"，无版权的歌会被当可播。
     */
    private fun parseItem(obj: JSONObject): PlaybackItem {
        val trial = obj.optJSONObject("freeTrialInfo")?.let {
            FreeTrialInfo(start = it.optInt("start", 0), end = it.optInt("end", 0))
        }
        val trialPrivilege = obj.optJSONObject("freeTrialPrivilege")?.let {
            FreeTrialPrivilege(
                resConsumable = it.optBoolean("resConsumable", false),
                userConsumable = it.optBoolean("userConsumable", false),
                cannotListenReason = if (it.isNull("cannotListenReason")) null
                    else it.optInt("cannotListenReason", 0),
                freeLimitTagType = optStringOrNull(it, "freeLimitTagType")
            )
        }
        return PlaybackItem(
            url = optStringOrNull(obj, "url"),
            code = obj.optInt("code", 200),
            level = optStringOrNull(obj, "level"),
            fee = obj.optInt("fee", 0),
            freeTrialInfo = trial,
            freeTrialPrivilege = trialPrivilege
        )
    }

    /** JSON null / 缺字段 / 空串 / 字面量 "null" 一律返回 null。 */
    private fun optStringOrNull(obj: JSONObject, key: String): String? {
        if (obj.isNull(key)) return null
        return obj.optString(key, "").takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun buildPayload(songId: Long, level: String): Map<String, String> {
        // 官方客户端 header 字段,声明 PC 端并携带随机 requestId,保证杜比等音质返回正常码率。
        val config = JSONObject()
            .put("os", "pc")
            .put("appver", "")
            .put("osver", "")
            .put("deviceId", "pyncm!")
            .put("requestId", (20_000_000..30_000_000).random().toString())

        val base = mutableMapOf(
            "ids" to JSONArray().put(songId).toString(),
            "level" to level,
            "header" to config.toString(),
        )
        // 杜比全景声必须以 mp4 容器输出(EAC3),其余音质用 FLAC。
        base["encodeType"] = if (level == "dolby") "mp4" else "flac"
        return base
    }
}
