package com.takahashirinta.ncrust.player

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
}
