package com.takahashirinta.ncrust.player

import androidx.media3.common.Player
import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.network.SongItem

/**
 * 播放队列与"播放器实际当前项"的纯映射逻辑。
 *
 * 设计原则（对应无缝播放的身份收敛）：播放模式只负责决定"下一个该是谁"，
 * 切歌后应用队列的当前位置一律以播放器回报的 mediaId 为准回查，绝不能靠
 * 自己再猜"播放器刚切到谁"——否则自然结束/错误重试/手动抢占会双重推进。
 *
 * 抽成纯函数便于在 JVM 层做单元测试。
 */
object PlaybackQueueLogic {

    /** 队列 + 当前索引的一次快照。纯值类型，便于单测与整块回写。 */
    data class QueueState(val queue: List<SongItem>, val currentIndex: Int)

    /**
     * 把播放器回报的 [actualSongId] 映射回应用队列索引。
     * 映射不到（队列被外部替换/歌曲已移除）时退回 [fallbackIndex]，并夹到合法范围。
     */
    fun resolveCurrentIndex(
        queue: List<SongItem>,
        actualSongId: Long?,
        fallbackIndex: Int
    ): Int {
        if (queue.isEmpty()) return -1
        if (actualSongId != null && actualSongId > 0) {
            val mapped = queue.indexOfFirst { it.id == actualSongId }
            if (mapped >= 0) return mapped
        }
        return fallbackIndex.coerceIn(0, queue.size - 1)
    }

    /**
     * 乱序模式下，让 [shuffledIndices] 的游标对齐到实际播放项所在的队列索引。
     * 找不到时保留 [fallbackPosition]（夹到合法范围）。
     */
    fun realignShuffledPosition(
        shuffledIndices: List<Int>,
        mappedIndex: Int,
        fallbackPosition: Int
    ): Int {
        if (shuffledIndices.isEmpty()) return 0
        val pos = shuffledIndices.indexOf(mappedIndex)
        return if (pos >= 0) pos else fallbackPosition.coerceIn(0, shuffledIndices.size - 1)
    }

    /**
     * 「当前播放项的下一首该是谁」的唯一决策点。
     *
     * 预载、自然衔接后的再预载、点队列切歌后的预载三处旧实现各抄了一份同样的
     * `when (playMode)`，任何一处改动不同步都会让"预载的歌"与"队列表里显示的下一首"
     * 分叉。返回 -1 表示按当前模式没有下一首（LINE / INFINITY 的队尾），调用方
     * 必须当作"不预载"处理，不能拿它当"预载第一首"。
     */
    fun nextIndex(
        queue: List<SongItem>,
        currentIndex: Int,
        mode: Int,
        shuffledIndices: List<Int> = emptyList(),
        shuffledPosition: Int = 0
    ): Int {
        if (queue.isEmpty() || currentIndex !in queue.indices) return -1
        return when (mode) {
            QueueModes.SINGLE -> currentIndex
            QueueModes.SHUFFLE -> {
                if (shuffledIndices.isEmpty()) return -1
                val pos = shuffledPosition.coerceIn(0, shuffledIndices.size - 1)
                val nextPos = if (pos < shuffledIndices.size - 1) pos + 1 else 0
                shuffledIndices.getOrElse(nextPos) { -1 }
            }
            // 顺序线性 / 相似无限：队尾之外没有下一首（队尾的续播由各自的分支处理）
            QueueModes.LINE, QueueModes.INFINITY ->
                if (currentIndex < queue.size - 1) currentIndex + 1 else -1
            // CYCLE: 队尾回绕队首；单曲队列不预载自身，否则 [A,A] 自动衔接 = 假单曲循环
            else -> if (queue.size > 1) {
                if (currentIndex < queue.size - 1) currentIndex + 1 else 0
            } else -1
        }
    }

    /**
     * 「播放/继续」之前是否需要先把播放器从"播完了"拉回来。
     *
     * ExoPlayer 的 `play()` 只把 playWhenReady 置 true：`setPlayWhenReadyInternal` 只在
     * READY/BUFFERING 时启动渲染器，STATE_ENDED 没有分支（media3 1.4.1），所以队尾播完后
     * 点播放键什么都不会发生——必须先 seekTo(0)+prepare 才能重新起播。
     * STATE_IDLE 同理（出错后停在 IDLE 时 prepare 才是重试）。
     */
    fun needsRestartBeforePlay(playbackState: Int, hasCurrentItem: Boolean): Boolean {
        if (!hasCurrentItem) return false
        return playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE
    }

    /**
     * 删除队列中的一项，并按**歌曲身份**重新定位当前索引。
     *
     * 旧实现只做 `currentIndex >= size` 的 clamp：删掉当前歌**之前**的项后索引数值不变，
     * 于是一格一格地指向了原当前歌的后一首——不变量 `queue[current] == 正在播的歌`
     * 当场破坏，之后所有"下一首/预载谁"的决策都从错位的位置开始算（听着就是
     * "点的歌和实际播的不是同一首"）。删掉的正是当前歌本身时退化为槽位语义：
     * 后一首顶上该槽位，队尾则前移一格（见 spec/fixtures/queue/README.md）。
     */
    fun removeAt(queue: List<SongItem>, currentIndex: Int, removeIndex: Int): QueueState {
        if (removeIndex !in queue.indices) return QueueState(queue, currentIndex)
        val currentId = queue.getOrNull(currentIndex)?.id
        val newQueue = queue.toMutableList().also { it.removeAt(removeIndex) }
        if (newQueue.isEmpty()) return QueueState(newQueue, -1)
        if (currentIndex < 0) return QueueState(newQueue, -1)
        // 按身份回查当前歌；找不到说明删掉的就是当前歌本身 —— 此时才用槽位语义。
        val relocated = if (currentId != null) newQueue.indexOfFirst { it.id == currentId } else -1
        val newIndex = if (relocated >= 0) relocated else minOf(currentIndex, newQueue.size - 1)
        return QueueState(newQueue, newIndex)
    }
}
