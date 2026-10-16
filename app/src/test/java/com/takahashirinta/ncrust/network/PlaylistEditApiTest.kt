package com.takahashirinta.ncrust.network

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 歌单写接口的请求体构造与响应解析单测。
 *
 * 这两块都是纯函数，写端点本身只有真机才跑，所以协议形态靠这里锁住：`op` 白名单、
 * `trackIds` 形态，以及「HTTP 200 但 body.code 决定成败」的全套映射。
 */
class PlaylistEditApiTest {

    @Test
    fun `add songs payload whitelists op add`() {
        val p = PlaylistEditApi.addSongsPayload(42L, listOf(1L, 2L, 3L))
        assertEquals("add", p["op"])
        assertEquals("42", p["pid"])
        assertEquals("[1,2,3]", p["trackIds"])
        assertEquals("true", p["imme"])
    }

    @Test
    fun `remove songs payload whitelists op del`() {
        val p = PlaylistEditApi.removeSongsPayload(42L, listOf(1L))
        assertEquals("del", p["op"])
        assertEquals("42", p["pid"])
        assertEquals("[1]", p["trackIds"])
        assertEquals("true", p["imme"])
    }

    @Test
    fun `privacy payload carries id and privacy`() {
        val p = PlaylistEditApi.privacyPayload(7L, PlaylistEditApi.PRIVACY_PRIVATE)
        assertEquals("7", p["id"])
        assertEquals("10", p["privacy"])
    }

    private fun body(code: Int, message: String = ""): String =
        JSONObject().put("code", code).put("message", message).toString()

    @Test
    fun `code 200 maps to success`() {
        assertTrue(PlaylistEditApi.parseWriteResponse(200, body(200)) is PlaylistWriteResult.Success)
    }

    @Test
    fun `code 502 maps to duplicate (idempotent success for add)`() {
        assertEquals(PlaylistWriteResult.Duplicate, PlaylistEditApi.parseWriteResponse(200, body(502)))
    }

    @Test
    fun `code 405 maps to rate limited`() {
        assertEquals(PlaylistWriteResult.RateLimited, PlaylistEditApi.parseWriteResponse(200, body(405)))
    }

    @Test
    fun `code 403 maps to illegal request`() {
        assertEquals(PlaylistWriteResult.IllegalRequest, PlaylistEditApi.parseWriteResponse(200, body(403)))
    }

    @Test
    fun `code 301 stays a failure carrying business code for session expiry`() {
        val result = PlaylistEditApi.parseWriteResponse(200, body(301, "需要登录"))
        assertTrue(result is PlaylistWriteResult.Failure)
        assertEquals(301, result.businessCode)
    }

    @Test
    fun `empty body is a failure regardless of http code`() {
        val result = PlaylistEditApi.parseWriteResponse(200, "")
        assertTrue(result is PlaylistWriteResult.Failure)
        assertEquals(-1, result.businessCode)
    }

    @Test
    fun `non json body is a failure`() {
        assertTrue(PlaylistEditApi.parseWriteResponse(200, "<html>403</html>") is PlaylistWriteResult.Failure)
    }

    @Test
    fun `msg field is accepted when message is absent`() {
        val result = PlaylistEditApi.parseWriteResponse(200, JSONObject().put("code", 400).put("msg", "参数错误").toString())
        assertEquals("参数错误", (result as PlaylistWriteResult.Failure).message)
    }

    @Test
    fun `playlist ownership requires not subscribed and matching creator`() {
        val mine = PlaylistApi.PlaylistInfo(1L, "n", "", 0, creatorUserId = 9L, specialType = 0, privacy = 0)
        val collected = mine.copy(subscribed = true)
        val theirPlaylist = mine.copy(creatorUserId = 5L)

        assertTrue(mine.isOwnedBy(9L))
        assertEquals(false, collected.isOwnedBy(9L))
        assertEquals(false, theirPlaylist.isOwnedBy(9L))
    }
}
