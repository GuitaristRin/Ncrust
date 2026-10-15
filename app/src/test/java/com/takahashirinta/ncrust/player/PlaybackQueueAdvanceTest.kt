package com.takahashirinta.ncrust.player

import androidx.media3.common.Player
import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.network.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「应用队列 → 给 ExoPlayer 的指令」这一层的模型级复现（#32 播错歌 / #29 播完不自动下一首）。
 *
 * 模型的事实依据（都来自 PlaybackService / PlaybackStateManager 的真实设计）：
 *  - ExoPlayer 播放列表里只保留「当前项 + 至多一首预载项」（`preload_next` 先清尾部再追加）；
 *  - `playFromQueue` → `playSong` 要先异步取链，取链完成后才 `setMediaItem`（整表替换）；
 *  - 自然衔接走 `onMediaItemTransition(AUTO)`，应用侧按"实际在播的 mediaId"回查索引。
 *
 * 这里**不含 ExoPlayer 本体**：它只能钉住决策层的契约，不能替代真机验证。
 */
class PlaybackQueueAdvanceTest {

    private fun song(id: Long) = SongItem(
        id = id,
        name = "song$id",
        artists = null,
        album = null,
        duration = null
    )

    private val queue = listOf(song(1), song(2), song(3), song(4))

    // ---------- #32：删掉当前歌之前的项 → 索引错位 → 预载/播出的不是队列表里的下一首 ----------

    /**
     * 旧实现只 clamp 索引：队列 [1,2,3,4]、正在播第 3 首(索引 2)，删掉第 1 首后索引仍是 2，
     * 于是"当前歌"变成第 4 首——而真正在响的还是第 3 首。按错位索引算出来的下一首是队首
     * 第 1 首(已经播过)，队列表里明明排在第 3 首后面的第 4 首反而不播。
     */
    @Test
    fun `stale index after removing a past row advances to a replayed song`() {
        val staleQueue = queue.toMutableList().also { it.removeAt(0) }   // [2,3,4]
        val staleIndex = 2                                              // 旧 clamp 的结果
        assertNotEquals("索引指向的歌必须不等于正在响的歌", song(3).id, staleQueue[staleIndex].id)
        assertEquals(0, PlaybackQueueLogic.nextIndex(staleQueue, staleIndex, QueueModes.CYCLE))
    }

    /** 同一序列按 spec/fixtures/queue 的身份语义：索引跟着第 3 首走到 1，下一首是第 4 首。 */
    @Test
    fun `identity relocation keeps the preload target on the real next song`() {
        val fixed = PlaybackQueueLogic.removeAt(queue, currentIndex = 2, removeIndex = 0)
        assertEquals(listOf(2L, 3L, 4L), fixed.queue.map { it.id })
        assertEquals(1, fixed.currentIndex)
        assertEquals(3L, fixed.queue[fixed.currentIndex].id)
        assertEquals(2, PlaybackQueueLogic.nextIndex(fixed.queue, fixed.currentIndex, QueueModes.CYCLE))
        assertEquals(4L, fixed.queue[2].id)
    }

    // ---------- #32：点歌的取链窗口里播放器自己衔接了 → 乐观索引错位 ----------

    /**
     * 队列 [1,2,3,4] 正在播第 1 首，ExoPlayer 里是 [1, 2(预载)]。用户点第 4 首：
     * `playFromQueue` 旧实现第一行就把应用索引推到第 4 首，而 `setMediaItem` 要等取链完成；
     * 这窗口里第 1 首自然结束 → 无缝交接到预载的第 2 首 → 索引被回查成第 2 首(1)；
     * 随后取链完成、`setMediaItem(第 4 首)`：音频在第 4 首，索引却停在 1 —— 不变量破裂，
     * 之后"下一首"算成第 3 首（队列表里它排在第 4 首之前）。
     */
    @Test
    fun `tap during in-flight url fetch leaves index on another song unless reconciled`() {
        var index = 3                                   // 乐观推进到用户点的第 4 首
        val actualAfterAutoTransition = song(2).id      // 窗口内播放器衔接到了预载的第 2 首
        val fallback = PlaybackQueueLogic.nextIndex(queue, index, QueueModes.CYCLE)
        index = PlaybackQueueLogic.resolveCurrentIndex(queue, actualAfterAutoTransition, fallback)
        assertEquals(1, index)

        val playingId = song(4).id                      // 取链完成，真正开始响的是第 4 首
        assertNotEquals(playingId, queue[index].id)
        assertEquals(2, PlaybackQueueLogic.nextIndex(queue, index, QueueModes.CYCLE))

        // 修复后的策略：每次"实际在播的歌"变化都按身份回查索引（MainScreen 的
        // LaunchedEffect(vmCurrentSongId)，等价于下面这一行）。
        index = queue.indexOfFirst { it.id == playingId }.takeIf { it >= 0 } ?: index
        assertEquals(playingId, queue[index].id)
        assertEquals(0, PlaybackQueueLogic.nextIndex(queue, index, QueueModes.CYCLE))
    }

    /** 系统媒体控件/车机走 media3 会话直接 seekToNextMediaItem：索引必须能跟着音频走回来。 */
    @Test
    fun `seek driven transition is reconciled back onto the playing song`() {
        var index = 0                                   // 应用以为在播第 1 首
        val playingId = song(2).id                      // 系统侧把当前项切到了预载的第 2 首
        assertEquals(0, index)
        index = queue.indexOfFirst { it.id == playingId }.takeIf { it >= 0 } ?: index
        assertEquals(1, index)
        assertEquals(playingId, queue[index].id)
    }

    // ---------- #29：播完停在末尾时"播放键"必须重开，play() 无效 ----------

    /**
     * ExoPlayer 的 `play()` 只改 playWhenReady：`setPlayWhenReadyInternal` 只在 READY/BUFFERING
     * 才启动渲染器，STATE_ENDED 没有分支（media3 1.4.1 实测反编译）。所以队尾播完后
     * 点播放键什么都不会发生，必须先 seekTo(0)+prepare 把播放器拉回可播状态。
     */
    @Test
    fun `play button must restart a player that reached the end`() {
        assertTrue(PlaybackQueueLogic.needsRestartBeforePlay(Player.STATE_ENDED, true))
        assertTrue(PlaybackQueueLogic.needsRestartBeforePlay(Player.STATE_IDLE, true))
        assertEquals(false, PlaybackQueueLogic.needsRestartBeforePlay(Player.STATE_READY, true))
        assertEquals(false, PlaybackQueueLogic.needsRestartBeforePlay(Player.STATE_ENDED, false))
    }

    // ---------- 播放模式的"下一首"契约（三处预载入口共用同一决策） ----------

    @Test
    fun `cycle wraps at the tail but a single-song queue has no next`() {
        assertEquals(1, PlaybackQueueLogic.nextIndex(queue, 0, QueueModes.CYCLE))
        assertEquals(0, PlaybackQueueLogic.nextIndex(queue, 3, QueueModes.CYCLE))
        assertEquals(-1, PlaybackQueueLogic.nextIndex(listOf(song(1)), 0, QueueModes.CYCLE))
    }

    @Test
    fun `single repeats itself, line and infinity stop at the tail`() {
        assertEquals(2, PlaybackQueueLogic.nextIndex(queue, 2, QueueModes.SINGLE))
        assertEquals(3, PlaybackQueueLogic.nextIndex(queue, 2, QueueModes.LINE))
        assertEquals(-1, PlaybackQueueLogic.nextIndex(queue, 3, QueueModes.LINE))
        assertEquals(3, PlaybackQueueLogic.nextIndex(queue, 2, QueueModes.INFINITY))
        assertEquals(-1, PlaybackQueueLogic.nextIndex(queue, 3, QueueModes.INFINITY))
    }

    @Test
    fun `shuffle advances through the shuffled table and wraps`() {
        val shuffled = listOf(2, 0, 3, 1)
        assertEquals(0, PlaybackQueueLogic.nextIndex(queue, 2, QueueModes.SHUFFLE, shuffled, 0))
        assertEquals(3, PlaybackQueueLogic.nextIndex(queue, 0, QueueModes.SHUFFLE, shuffled, 1))
        assertEquals(2, PlaybackQueueLogic.nextIndex(queue, 1, QueueModes.SHUFFLE, shuffled, 3))
        // 打乱表缺失时不能拿"队首"顶替：宁可没有下一首，交给播放结束分支重建打乱表
        assertEquals(-1, PlaybackQueueLogic.nextIndex(queue, 2, QueueModes.SHUFFLE, emptyList(), 0))
    }

    @Test
    fun `next index is minus one for invalid input`() {
        assertEquals(-1, PlaybackQueueLogic.nextIndex(emptyList(), 0, QueueModes.CYCLE))
        assertEquals(-1, PlaybackQueueLogic.nextIndex(queue, -1, QueueModes.CYCLE))
        assertEquals(-1, PlaybackQueueLogic.nextIndex(queue, 9, QueueModes.CYCLE))
    }
}
