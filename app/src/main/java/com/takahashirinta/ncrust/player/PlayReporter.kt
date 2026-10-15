//! 播放行为上报（听歌打卡）。
//!
//! 通道：`POST https://clientlog.music.163.com/eapi/feedback/weblog`，**eapi 加密 + os=osx 身份**。
//! 旧通道（明文表单打 clientlogusf）整套删掉了：2026 年社区对照实验
//! （NeteaseCloudMusicApiEnhanced/api-enhanced#167，维护者已据此在 4.35.2 修复）实测
//! weapi / eapi(interface) / clientlogusf 三条都回 `code 200` 却**被服务端静默丢弃、不入账**，
//! 只有这一组合真正进「最近播放 / 听歌排行 / 每日推荐」。
//!
//! 每首歌发两条（缺一不可，字段依据参考实现 module/scrobble.js，协议见 spec/api/endpoints.md）：
//!   startplay → 进「最近播放」；play → 涨「听歌排行」。业务字段只有一个 `logs`（JSON 文本）。
//!
//! ⚠️ 验证边界：本文件与单测锁的是**请求形态**（URL / 身份 / 字段与顺序）；「服务端是否真的入账」
//! 只能由账号主人在官方 App 里核对 —— 匿名或无效 cookie 的探针拿到的 200 恰恰是旧通道那种「假成功」。

package com.takahashirinta.ncrust.player

import android.util.Log
import com.google.gson.GsonBuilder
import com.takahashirinta.ncrust.network.RetrofitClient
import kotlin.concurrent.thread

object PlayReporter {
    private const val TAG = "PlayReporter"

    /** 业务路径按 `/api/…` 形态书写（请求走 `/eapi/…`，签名用这个）。 */
    const val WEBLOG_PATH = "/api/feedback/weblog"

    /** 判定"听完"的进度阈值。 */
    private const val COMPLETION_THRESHOLD = 0.8f

    // Gson 默认会把 HTML 字符转义（`id=` 会变成 `id\u003d`）。参考实现用 JSON.stringify，
    // 不做这种转义 —— 要的是与它逐字符同形的请求体，所以关掉。
    // 用 Gson 而不是 org.json 的另一个原因：org.json 在本地 JVM 单测里是空桩，
    // 换成它就没法在不上设备的情况下断言字段与顺序。
    private val gson = GsonBuilder().disableHtmlEscaping().create()

    /**
     * 「开始播放」那一条的 `logs` 文本（进「最近播放」）。
     *
     * @param sourceId 歌曲来源的歌单 / 专辑 id，**不知道就给空串**（`content` 即 `id=`），不要编。
     */
    fun startplayLogs(songId: Long, sourceId: String = ""): String =
        encode(
            "startplay",
            linkedMapOf(
                "id" to songId,
                "type" to "song",
                "mainsite" to "1",
                "mainsiteWeb" to "1",
                "content" to "id=$sourceId",
            )
        )

    /**
     * 「听完」那一条的 `logs` 文本（涨「听歌排行」）。
     *
     * @param playedMs 实际播放毫秒数；参考实现与服务端要的 `time` 是**秒**，这里换算
     *   （旧通道发的是毫秒，是这次随通道一起纠正的点）。
     * @param isWifi 是否 Wi-Fi：`wifi` 记 0（0 = Wi-Fi，1 = 计费网络）。
     */
    fun playLogs(
        songId: Long,
        playedMs: Long,
        sourceId: String = "",
        isWifi: Boolean = false,
    ): String =
        encode(
            "play",
            linkedMapOf(
                "download" to 0,
                "end" to "playend",
                "id" to songId,
                "sourceId" to sourceId,
                "time" to playedMs / 1000,
                "type" to "song",
                "wifi" to if (isWifi) 0 else 1,
                "source" to "list",
                "mainsite" to "1",
                "mainsiteWeb" to "1",
                "content" to "id=$sourceId",
            )
        )

    /** 开始播放一首歌：发 startplay。每首一次的判定在调用方（`PlayerViewModel`）。 */
    fun reportStart(songId: Long, sourceId: String = "") {
        send(startplayLogs(songId, sourceId))
    }

    /**
     * 听完一首歌：发 play。触发时机（自然结束 / 进度 ≥ 80%）与每首一次的判定都在调用方
     * （`PlayerViewModel`）。
     */
    fun reportPlay(
        songId: Long,
        playedMs: Long,
        isWifi: Boolean = false,
        sourceId: String = "",
    ) {
        send(playLogs(songId, playedMs, sourceId, isWifi))
    }

    /** 是否已达到"听完"阈值。 */
    fun reachedCompletion(positionMs: Long, durationMs: Long): Boolean {
        return durationMs > 0 && positionMs.toFloat() / durationMs.toFloat() >= COMPLETION_THRESHOLD
    }

    /**
     * 日志数组的 JSON 文本。`action` 在前、`json` 在后，`json` 内部按传入顺序写 ——
     * 顺序与参考实现一致，单测直接逐字符比对。
     */
    private fun encode(action: String, fields: Map<String, Any>): String =
        gson.toJson(listOf(linkedMapOf("action" to action, "json" to fields)))

    /**
     * 发出一次上报：单独一条线程 + 立刻返回，**不阻塞播放**，失败只记日志（尽力而为）。
     *
     * 没登录（Cookie 里没有 MUSIC_U）直接不发：这条通道的身份就是登录态，匿名请求没有意义。
     */
    private fun send(logs: String) {
        val cookie = RetrofitClient.getCookie() ?: return
        if (!cookie.contains("MUSIC_U")) return

        thread(name = "ncrust-scrobble") {
            try {
                val resp = RetrofitClient.eapiPostOsx(
                    WEBLOG_PATH,
                    linkedMapOf<String, Any>("logs" to logs)
                )
                Log.d(TAG, "scrobble resp: ${resp.code} bytes=${logs.length}")
                resp.close()
            } catch (e: Exception) {
                Log.w(TAG, "scrobble report failed", e)
            }
        }
    }
}
