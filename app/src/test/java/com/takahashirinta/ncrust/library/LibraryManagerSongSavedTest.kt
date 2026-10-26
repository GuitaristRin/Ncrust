package com.takahashirinta.ncrust.library

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住「这首歌算已收藏吗」的判定（`LibraryManager.isSongSavedIn`）。
 *
 * 缺陷背景（issue #33）：红心歌单分页加载时 `cachedSongs` 只有前 N 首，只看它会
 * 把超出已加载页的红心歌误判成「未收藏」——播放器收藏按钮因此显示白 +，点一下
 * 反而触发一次多余的收藏写入。红心 id 底表（`liked_ids`）是全量真源，命中即已收藏；
 * 已加载列表只兜底老数据（底表为空、本地列表有歌）的场景。
 */
class LibraryManagerSongSavedTest {

    private fun song(id: Long) = SongItem(
        id = id,
        name = "song$id",
        artists = listOf(ArtistItem(id = id, name = "artist$id")),
        album = AlbumItem(id = id, name = "album$id", picUrl = "https://pic/$id"),
        duration = 200_000L + id
    )

    @Test
    fun `liked song beyond the loaded pages still counts as saved`() {
        // 底表有 999、已加载列表为空：这正是分页只拉了前几页时的真实形态。
        assertTrue(
            LibraryManager.isSongSavedIn(likedIds = listOf(1L, 500L, 999L), loadedSongs = emptyList(), songId = 999L)
        )
    }

    @Test
    fun `loaded list is the fallback when the id table is empty`() {
        // 老数据路径：底表还没同步过，本地列表里有歌。
        assertTrue(
            LibraryManager.isSongSavedIn(likedIds = emptyList(), loadedSongs = listOf(song(7)), songId = 7L)
        )
    }

    @Test
    fun `song in neither source is not saved`() {
        assertFalse(
            LibraryManager.isSongSavedIn(likedIds = listOf(1L, 2L), loadedSongs = listOf(song(3)), songId = 4L)
        )
    }

    @Test
    fun `union semantics - a song only in the loaded list still counts as saved`() {
        // 判定是并集（底表 ∪ 已加载列表），不是底表独大：老数据路径（底表从未同步、
        // 本地列表有歌）依赖这个兜底。反过来意味着「底表已摘、列表还滞后」的窗口里
        // 会仍判已收藏——宁可多显示一次收藏，也不把本地确实列着的歌显示成未收藏。
        assertTrue(
            LibraryManager.isSongSavedIn(likedIds = listOf(1L), loadedSongs = listOf(song(2)), songId = 2L)
        )
    }
}
