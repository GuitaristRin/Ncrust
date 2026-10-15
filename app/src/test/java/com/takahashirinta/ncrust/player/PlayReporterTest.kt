package com.takahashirinta.ncrust.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 播放上报的 `logs` 构造单测：字段、字段顺序、单位换算。
 *
 * 这两种 logs 的形态是协议的一部分（依据参考实现 `module/scrobble.js`，协议见
 * `spec/api/endpoints.md` 的「播放上报 / 听歌打卡」）：顺序错、`time` 用了毫秒、或少一个
 * `mainsite`，服务端不会报错，只是不入账 —— 所以这里逐字符比对，而不是只看字段在不在。
 *
 * 边界：真正把 logs 塞进加密信封的是 `EapiCrypto`（`org.json` 序列化），本地单测里 org.json
 * 是空桩；这里断言的是**交给它的那份文本**，信封形态由
 * `RetrofitClientOsxHeaderTest`（Gson 渲染同一份 Map）与 Windows Core 测试（解真请求体）覆盖。
 */
class PlayReporterTest {

    @Test
    fun `startplay logs match the reference shape`() {
        assertEquals(
            "[{\"action\":\"startplay\",\"json\":{" +
                "\"id\":123,\"type\":\"song\",\"mainsite\":\"1\",\"mainsiteWeb\":\"1\",\"content\":\"id=\"}}]",
            PlayReporter.startplayLogs(123)
        )
    }

    @Test
    fun `play logs match the reference shape`() {
        assertEquals(
            "[{\"action\":\"play\",\"json\":{" +
                "\"download\":0,\"end\":\"playend\",\"id\":123,\"sourceId\":\"\",\"time\":45," +
                "\"type\":\"song\",\"wifi\":0,\"source\":\"list\"," +
                "\"mainsite\":\"1\",\"mainsiteWeb\":\"1\",\"content\":\"id=\"}}]",
            PlayReporter.playLogs(123, playedMs = 45_000, isWifi = true)
        )
    }

    @Test
    fun `play time is seconds not milliseconds`() {
        // 旧通道发的是毫秒；参考实现与服务端要的是秒（本次随通道一起纠正）。
        assertTrue(PlayReporter.playLogs(1, playedMs = 45_000).contains("\"time\":45,"))
        assertTrue(PlayReporter.playLogs(1, playedMs = 999).contains("\"time\":0,"))
    }

    @Test
    fun `wifi field is 0 on wifi and 1 on metered`() {
        assertTrue(PlayReporter.playLogs(1, 1_000, isWifi = true).contains("\"wifi\":0,"))
        assertTrue(PlayReporter.playLogs(1, 1_000, isWifi = false).contains("\"wifi\":1,"))
    }

    @Test
    fun `source id absent means empty string and content keeps the id= form`() {
        // 不知道来源歌单 / 专辑时用空串，不要编一个 id 出来。
        assertTrue(PlayReporter.startplayLogs(7).contains("\"content\":\"id=\""))
        assertTrue(PlayReporter.playLogs(7, 1_000).contains("\"sourceId\":\"\",\"time\":"))
    }

    @Test
    fun `source id flows into sourceId and content`() {
        val startplay = PlayReporter.startplayLogs(7, sourceId = "501")
        val play = PlayReporter.playLogs(7, 1_000, sourceId = "501")

        assertTrue(startplay.contains("\"content\":\"id=501\""))
        assertTrue(play.contains("\"sourceId\":\"501\""))
        assertTrue(play.contains("\"content\":\"id=501\""))
    }

    @Test
    fun `reached completion threshold is 80 percent`() {
        assertFalse(PlayReporter.reachedCompletion(79, 100))
        assertTrue(PlayReporter.reachedCompletion(80, 100))
        assertTrue(PlayReporter.reachedCompletion(100, 100))
        // 时长未知（还没读到 duration）时不能算听完。
        assertFalse(PlayReporter.reachedCompletion(0, 0))
    }
}
