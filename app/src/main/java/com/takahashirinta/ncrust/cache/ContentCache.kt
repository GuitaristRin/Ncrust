package com.takahashirinta.ncrust.cache

import androidx.collection.LruCache
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumDetailResponse
import com.takahashirinta.ncrust.network.model.ArtistAlbumsResponse

// In-memory 缓存网络加载内容。进程存活期间常驻，进程被杀后失效。
//
// 目的：消除"空屏 -> spinner -> 内容跳变"的 UX。
// 新的加载策略：
//   1. 屏幕进入时读缓存作为初始 state，有则直接渲染。
//   2. 无论是否命中缓存，后台都发起刷新请求。
//   3. 请求返回后写回缓存，UI 层用 Crossfade 平滑替换。
//
// 与 LibraryManager 的区别：LibraryManager 是用户主动收藏的持久化数据（SharedPreferences），
// ContentCache 是网络加载的临时数据快照，不需要持久化。
//
// 详情页 cache 用 LruCache(32) 封顶，避免低端机无界增长导致 OOM。
// 首页三个字段是单值，不会增长，无需 LRU。
object ContentCache {
    // ── 首页 ─────────────────────────────────────────────────────────
    @Volatile var homeDailySongs: List<SongItem>? = null
    @Volatile var homeRecommendPlaylists: List<PlaylistApi.PlaylistCard>? = null
    @Volatile var homeNewSongs: List<SongItem>? = null

    // ── 详情页（按 ID 缓存，LRU 32 项封顶） ──────────────────────────
    private val albumCache = LruCache<Long, AlbumDetailResponse>(32)
    private val playlistCache = LruCache<Long, List<SongItem>>(32)
    private val artistCache = LruCache<Long, ArtistAlbumsResponse>(32)

    fun getAlbum(id: Long): AlbumDetailResponse? = albumCache[id]
    fun putAlbum(id: Long, data: AlbumDetailResponse) { albumCache.put(id, data) }

    fun getPlaylistSongs(id: Long): List<SongItem>? = playlistCache[id]
    fun putPlaylistSongs(id: Long, data: List<SongItem>) { playlistCache.put(id, data) }

    fun getArtistAlbums(id: Long): ArtistAlbumsResponse? = artistCache[id]
    fun putArtistAlbums(id: Long, data: ArtistAlbumsResponse) { artistCache.put(id, data) }

    // ── 用户 ─────────────────────────────────────────────────────────
    @Volatile var userProfile: PlaylistApi.UserProfile? = null

    // 清空所有缓存（切换账号 / 内存压力 / 进程重启场景）。
    fun clearAll() {
        homeDailySongs = null
        homeRecommendPlaylists = null
        homeNewSongs = null
        albumCache.evictAll()
        playlistCache.evictAll()
        artistCache.evictAll()
        userProfile = null
    }
}
