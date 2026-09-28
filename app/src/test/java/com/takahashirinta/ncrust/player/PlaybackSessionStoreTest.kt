package com.takahashirinta.ncrust.player

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 锁住播放会话的落盘格式：release 下 R8 会剪掉未 keep 类的字段签名，会话序列化
// 必须走显式键信封 + 匿名 TypeToken（android-v1.3.2 首版闪退的根因，详见
// PlaybackStateManager 信封键注释）。这两个用例防止任何一环被悄悄改回去。
class PlaybackSessionStoreTest {

    private fun song(id: Long) = SongItem(
        id = id,
        name = "song$id",
        artists = listOf(ArtistItem(id = id, name = "artist$id")),
        album = AlbumItem(id = id, name = "album$id", picUrl = "https://pic/$id"),
        duration = 200_000L + id
    )

    private fun session() = PlaybackStateManager.PlaybackSession(
        queue = listOf(song(1), song(2), song(3)),
        queueIndex = 1,
        playMode = 4,
        fmMode = true,
        shuffledIndices = listOf(2, 0, 1),
        shuffledPosition = 1
    )

    @Test
    fun `envelope round trip preserves queue and session fields`() {
        val parsed = PlaybackStateManager.parseSession(
            PlaybackStateManager.serializeSession(session())
        )
        assertEquals(session(), parsed)
    }

    @Test
    fun `envelope uses explicit keys not data class field names`() {
        val obj = JsonParser.parseString(
            PlaybackStateManager.serializeSession(session())
        ).asJsonObject
        assertTrue(obj.has("queue"))
        assertTrue(obj.get("queue").isJsonArray)
        assertTrue(obj.has("queueIndex"))
        assertTrue(obj.has("playMode"))
        assertTrue(obj.has("fmMode"))
        assertTrue(obj.has("shuffledIndices"))
        assertTrue(obj.has("shuffledPosition"))
        // PlaybackSession 未 keep，字段若被混淆（a~f）绝不能出现在落盘格式里。
        assertFalse(obj.has("a"))
    }

    @Test
    fun `reads legacy v132 envelope written under obfuscated field names`() {
        // android-v1.3.2 发布包以 Gson 反射整个 PlaybackSession 落盘，键为混淆字段名 a~f。
        val legacy = JsonObject().apply {
            add("a", JsonParser.parseString(
                PlaybackStateManager.serializeSession(
                    PlaybackStateManager.PlaybackSession(queue = listOf(song(7), song(8)))
                )
            ).asJsonObject.get("queue"))
            addProperty("b", 1)
            addProperty("c", 2)
            addProperty("d", true)
            add("e", JsonParser.parseString("[1,0]"))
            addProperty("f", 1)
        }
        val parsed = PlaybackStateManager.parseSession(legacy.toString())
        assertEquals(listOf(song(7), song(8)), parsed?.queue)
        assertEquals(1, parsed?.queueIndex)
        assertEquals(2, parsed?.playMode)
        assertEquals(true, parsed?.fmMode)
        assertEquals(listOf(1, 0), parsed?.shuffledIndices)
        assertEquals(1, parsed?.shuffledPosition)
    }

    @Test
    fun `returns null when queue missing`() {
        // 信封里没有队列（键缺失或显式 null）就不恢复，绝不构造空元素列表。
        assertNull(PlaybackStateManager.parseSession("{}"))
        assertNull(PlaybackStateManager.parseSession("{\"queue\":null}"))
    }

    @Test
    fun `empty session fields survive round trip with defaults`() {
        val empty = PlaybackStateManager.PlaybackSession()
        assertEquals(empty, PlaybackStateManager.parseSession(
            PlaybackStateManager.serializeSession(empty)
        ))
    }
}
