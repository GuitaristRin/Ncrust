package com.takahashirinta.ncrust.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住「待同步重试的方向取自待同步表」这条规则（`LibraryManager.retryPendingSync`）。
 *
 * 旧实现用 `isSongSaved(context, songId)` 反推期望方向，而 `cachedSongs` 只装了已分页加载的
 * 前 N 首：云端「收藏」还没最终一致时那首歌不在本地列表里，反推得到 `false`，于是一次「收藏」
 * 被同步成 unlike —— 收藏真的被撤销，读写校验随后还会把它当成"已解决"移出待同步表。
 * 这两个用例分别覆盖两个方向，`planPendingLikeRetries` 是现在唯一的方向来源。
 */
class LibraryManagerPendingSyncTest {

    @Test
    fun `recorded like is retried as like even when the song is not in the loaded page`() {
        // 场景：待同步表记着「收藏」，但本地只加载了首屏（分页前）——这首歌还不在里面。
        val loadedPage = emptyList<Long>()
        assertTrue("前置：这正是旧实现反推出 false 的场景", 2754388325L !in loadedPage)

        assertEquals(
            listOf(2754388325L to true),
            LibraryManager.planPendingLikeRetries(mapOf(2754388325L to true))
        )
    }

    @Test
    fun `recorded unlike is retried as unlike even when the song is still in the loaded page`() {
        // 反方向同理：刚取消收藏的歌可能还在本地缓存里，反推会把 unlike 变成 like（收藏自己回来）。
        val loadedPage = listOf(347230L, 186016L)
        assertTrue("前置：这正是旧实现反推出 true 的场景", 347230L in loadedPage)

        assertEquals(
            listOf(347230L to false),
            LibraryManager.planPendingLikeRetries(mapOf(347230L to false))
        )
    }

    @Test
    fun `empty pending table produces no work`() {
        assertEquals(emptyList<Pair<Long, Boolean>>(), LibraryManager.planPendingLikeRetries(emptyMap()))
    }

    @Test
    fun `mixed pending table keeps every entry and its direction`() {
        val pending = linkedMapOf(1L to true, 2L to false, 3L to true)
        assertEquals(listOf(1L to true, 2L to false, 3L to true), LibraryManager.planPendingLikeRetries(pending))
    }
}
