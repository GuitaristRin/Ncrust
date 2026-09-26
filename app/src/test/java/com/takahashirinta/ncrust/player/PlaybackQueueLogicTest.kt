package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.network.SongItem
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueLogicTest {

    private fun song(id: Long) = SongItem(
        id = id,
        name = "song$id",
        artists = null,
        album = null,
        duration = null
    )

    private var queue = listOf(song(1), song(2), song(3), song(4))

    @Test
    fun `resolve maps by actual mediaId even when fallback disagrees`() {
        // 播放模式猜的下一个索引是 1，但播放器实际切到了 id=3（索引 2）——以媒体项为准。
        val idx = PlaybackQueueLogic.resolveCurrentIndex(queue, actualSongId = 3L, fallbackIndex = 1)
        assertEquals(2, idx)
    }

    @Test
    fun `resolve falls back when actual id absent from queue`() {
        val idx = PlaybackQueueLogic.resolveCurrentIndex(queue, actualSongId = 999L, fallbackIndex = 2)
        assertEquals(2, idx)
    }

    @Test
    fun `resolve falls back when actual id missing or invalid`() {
        assertEquals(0, PlaybackQueueLogic.resolveCurrentIndex(queue, null, 0))
        assertEquals(3, PlaybackQueueLogic.resolveCurrentIndex(queue, -1L, 3))
    }

    @Test
    fun `resolve clamps fallback into range`() {
        assertEquals(3, PlaybackQueueLogic.resolveCurrentIndex(queue, actualSongId = null, fallbackIndex = 99))
        assertEquals(0, PlaybackQueueLogic.resolveCurrentIndex(queue, actualSongId = null, fallbackIndex = -5))
    }

    @Test
    fun `resolve returns minus one for empty queue`() {
        assertEquals(-1, PlaybackQueueLogic.resolveCurrentIndex(emptyList(), 5L, 0))
    }

    @Test
    fun `first duplicate id wins`() {
        val dup = listOf(song(1), song(2), song(1))
        assertEquals(0, PlaybackQueueLogic.resolveCurrentIndex(dup, actualSongId = 1L, fallbackIndex = 2))
    }

    @Test
    fun `realign finds shuffled position of mapped index`() {
        val shuffled = listOf(2, 0, 3, 1)
        assertEquals(2, PlaybackQueueLogic.realignShuffledPosition(shuffled, mappedIndex = 3, fallbackPosition = 0))
    }

    @Test
    fun `realign keeps fallback when mapped index not in shuffled list`() {
        val shuffled = listOf(2, 0, 3, 1)
        assertEquals(1, PlaybackQueueLogic.realignShuffledPosition(shuffled, mappedIndex = 99, fallbackPosition = 1))
    }

    @Test
    fun `realign empty shuffled list returns zero`() {
        assertEquals(0, PlaybackQueueLogic.realignShuffledPosition(emptyList(), mappedIndex = 2, fallbackPosition = 3))
    }
}
