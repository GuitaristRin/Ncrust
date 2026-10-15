package com.takahashirinta.ncrust.library

import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住「我喜欢的音乐」整列播放的取全量逻辑（`LibraryManager.assembleLikedSongs`）。
 *
 * 这条路径的底表是红心 id 表（全量、有序），而 `cachedSongs` 只有已分页加载的前 N 首——
 * 拿后者当"全部"就是 issue #30 里用户看到的那半截列表。用例分别锁住：id 表顺序即输出顺序、
 * 缺失 id 按批补且批量有上界、缓存命中不重复取网络、取不到详情的 id 跳过、单批失败不拖垮整次。
 */
class LibraryManagerLikedSongsTest {

    private fun song(id: Long) = SongItem(
        id = id,
        name = "song$id",
        artists = listOf(ArtistItem(id = id, name = "artist$id")),
        album = AlbumItem(id = id, name = "album$id", picUrl = "https://pic/$id"),
        duration = 200_000L + id
    )

    /**
     * 假批量取数：记录每次收到的批次；故意**倒序**返回，好让"输出是否保序"这件事真的被验证
     * （照请求顺序返回的话，不保序的实现也能蒙混过关）。[failOnCalls] 里的第 n 次调用抛异常。
     */
    private class RecordingFetch(
        private val byId: Map<Long, SongItem>,
        private val failOnCalls: Set<Int> = emptySet()
    ) {
        val batches = mutableListOf<List<Long>>()

        suspend fun fetch(ids: List<Long>): List<SongItem> {
            batches += ids
            if (batches.size in failOnCalls) throw IllegalStateException("simulated batch failure")
            return ids.mapNotNull { byId[it] }.reversed()
        }
    }

    @Test
    fun `preserves the liked id order regardless of fetch order`() = runBlocking {
        val ids = listOf(3L, 1L, 2L)
        val fetch = RecordingFetch(ids.associateWith { song(it) })

        val out = LibraryManager.assembleLikedSongs(ids, emptyMap(), 500, fetch::fetch)

        assertEquals(listOf(3L, 1L, 2L), out.map { it.id })
    }

    @Test
    fun `splits missing ids into batches no larger than the limit`() = runBlocking {
        assertTrue(
            "补详情的批次上界必须 ≤ 500（任务书硬要求），当前 ${LibraryManager.LIKED_FETCH_BATCH_SIZE}",
            LibraryManager.LIKED_FETCH_BATCH_SIZE <= 500
        )
        val ids = (1L..1200L).toList()
        val fetch = RecordingFetch(ids.associateWith { song(it) })

        // 不传 batchSize，走真实默认值——上界被改大时这个用例会红。
        val out = LibraryManager.assembleLikedSongs(ids, emptyMap(), fetchBatch = fetch::fetch)

        assertEquals(listOf(500, 500, 200), fetch.batches.map { it.size })
        assertEquals(ids, out.map { it.id })
    }

    @Test
    fun `cached ids skip the network and keep the cached instance`() = runBlocking {
        val cachedSong = song(2)
        val ids = listOf(1L, 2L, 3L)
        // 网络侧对 id=2 返回另一份"歌"：只有缓存命中优先才能保证拿到 cachedSong。
        val fetch = RecordingFetch(mapOf(1L to song(1), 2L to song(99), 3L to song(3)))

        val out = LibraryManager.assembleLikedSongs(ids, mapOf(2L to cachedSong), 500, fetch::fetch)

        assertEquals(listOf(1L, 3L), fetch.batches.single())
        assertEquals(listOf(1L, 2L, 3L), out.map { it.id })
        assertSame("命中缓存的 id 不能被网络结果顶掉", cachedSong, out[1])
    }

    @Test
    fun `ids without details are skipped and the rest keep their order`() = runBlocking {
        val ids = listOf(10L, 11L, 12L, 13L)
        // 11、13 取不到详情（已下架/接口漏返）：跳过，不占位也不编数据。
        val fetch = RecordingFetch(mapOf(10L to song(10), 12L to song(12)))

        val out = LibraryManager.assembleLikedSongs(ids, emptyMap(), 500, fetch::fetch)

        assertEquals(listOf(10L, 12L), out.map { it.id })
    }

    @Test
    fun `a failed batch only drops that batch`() = runBlocking {
        val ids = (1L..600L).toList()
        val fetch = RecordingFetch(ids.associateWith { song(it) }, failOnCalls = setOf(1))

        val out = LibraryManager.assembleLikedSongs(ids, emptyMap(), 500, fetch::fetch)

        assertEquals(listOf(500, 100), fetch.batches.map { it.size })
        assertEquals("第一批失败应留下第二批，而不是整次放弃", (501L..600L).toList(), out.map { it.id })
    }

    @Test
    fun `everything cached means no network at all`() = runBlocking {
        val ids = listOf(7L, 8L)
        val fetch = RecordingFetch(emptyMap())

        val out = LibraryManager.assembleLikedSongs(ids, ids.associateWith { song(it) }, 500, fetch::fetch)

        assertEquals(0, fetch.batches.size)
        assertEquals(ids, out.map { it.id })
    }

    @Test
    fun `empty id table yields nothing`() = runBlocking {
        val fetch = RecordingFetch(emptyMap())

        val out = LibraryManager.assembleLikedSongs(emptyList(), emptyMap(), 500, fetch::fetch)

        assertEquals(emptyList<SongItem>(), out)
        assertEquals(0, fetch.batches.size)
    }
}
