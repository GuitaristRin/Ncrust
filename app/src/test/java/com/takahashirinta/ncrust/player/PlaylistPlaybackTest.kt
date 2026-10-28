package com.takahashirinta.ncrust.player

import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.network.SongItem
import org.junit.Assert.*
import org.junit.Test
import kotlin.random.Random

class PlaylistPlaybackTest {
    private fun song(id: Long, name: String) = SongItem(id, name, null, null, 0)
    @Test fun `clicking B keeps original order and real index`() {
        val songs = listOf(song(1, "A"), song(2, "B"), song(3, "C"), song(2, "B"))
        val state = PlaybackQueueLogic.playlistStart(songs, 2)
        assertEquals(listOf(1L, 2L, 3L), state.queue.map { it.id })
        assertEquals(1, state.currentIndex)
        assertEquals(2, PlaybackQueueLogic.advanceIndex(3, state.currentIndex, QueueModes.CYCLE, true))
        assertEquals(0, PlaybackQueueLogic.advanceIndex(3, 2, QueueModes.CYCLE, true))
    }
    @Test fun `missing starting ID never silently plays first song`() {
        assertEquals(-1, PlaybackQueueLogic.playlistStart(listOf(song(1, "A")), 5).currentIndex)
    }
    @Test fun `single repeats on natural end but manual next advances`() {
        assertEquals(1, PlaybackQueueLogic.advanceIndex(3, 1, QueueModes.SINGLE, true))
        assertEquals(2, PlaybackQueueLogic.advanceIndex(3, 1, QueueModes.SINGLE, false))
    }
    @Test fun `linear tail has no next index and current identity stays last`() {
        assertEquals(-1, PlaybackQueueLogic.advanceIndex(3, 2, QueueModes.LINE, true))
        assertEquals(-1, PlaybackQueueLogic.advanceIndex(3, 2, QueueModes.LINE, false))
    }
    @Test fun `new shuffle round is complete and never immediately repeats tail`() {
        for (seed in 0 until 50) {
            val round = PlaybackQueueLogic.newShuffleRound(8, 3, Random(seed))
            assertEquals((0 until 8).toSet(), round.toSet())
            assertEquals(8, round.size)
            assertNotEquals(3, round.first())
        }
        assertEquals(listOf(0), PlaybackQueueLogic.newShuffleRound(1, 0))
        assertTrue(PlaybackQueueLogic.newShuffleRound(0, -1).isEmpty())
    }
}
