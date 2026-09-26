using System;
using System.Collections.Generic;
using Ncrust.Core.Api;

namespace Ncrust.Core.Cache
{
    /// <summary>
    /// 网络内容的**内存**快照（对应 Android <c>ContentCache.kt</c>）。进程存活期间常驻，
    /// 不持久化（持久化的是 <c>LibraryManager</c>）。目的：进入页面先渲染缓存，再后台刷新，
    /// 消除「空屏 → spinner → 内容跳变」。
    ///
    /// 首页三个列表是单值；详情用 LRU-32 封顶，避免无界增长。
    /// </summary>
    public sealed class ContentCache
    {
        public const long HomeFreshTtlMs = 15_000;

        private readonly Func<long> _clock;
        private readonly LruCache<long, AlbumDetailResult> _album = new LruCache<long, AlbumDetailResult>(32);
        private readonly LruCache<long, IReadOnlyList<SongItem>> _playlist = new LruCache<long, IReadOnlyList<SongItem>>(32);
        private readonly LruCache<long, ArtistAlbumsResult> _artist = new LruCache<long, ArtistAlbumsResult>(32);

        private long _homeWarmedUpAt;

        public ContentCache(Func<long>? clock = null) =>
            _clock = clock ?? (() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());

        public IReadOnlyList<SongItem>? HomeDailySongs { get; set; }

        public IReadOnlyList<PlaylistCard>? HomeRecommendPlaylists { get; set; }

        public IReadOnlyList<SongItem>? HomeNewSongs { get; set; }

        public UserProfile? UserProfile { get; set; }

        /// <summary>记录首页最近一次预热时间，供 Home 判断是否还要再拉一遍。</summary>
        public void MarkHomeWarmed() => _homeWarmedUpAt = _clock();

        public bool IsHomeFresh(long ttlMs = HomeFreshTtlMs) =>
            _homeWarmedUpAt > 0 && _clock() - _homeWarmedUpAt < ttlMs;

        public AlbumDetailResult? GetAlbum(long id) => _album.TryGet(id, out var value) ? value : null;

        public void PutAlbum(long id, AlbumDetailResult data) => _album.Put(id, data);

        public IReadOnlyList<SongItem>? GetPlaylistSongs(long id) =>
            _playlist.TryGet(id, out var value) ? value : null;

        public void PutPlaylistSongs(long id, IReadOnlyList<SongItem> data) => _playlist.Put(id, data);

        public ArtistAlbumsResult? GetArtistAlbums(long id) => _artist.TryGet(id, out var value) ? value : null;

        public void PutArtistAlbums(long id, ArtistAlbumsResult data) => _artist.Put(id, data);

        /// <summary>切换账号 / 内存压力时清空（对应 Android 的 onTrimMemory / 手动清缓存）。</summary>
        public void ClearAll()
        {
            HomeDailySongs = null;
            HomeRecommendPlaylists = null;
            HomeNewSongs = null;
            _album.Clear();
            _playlist.Clear();
            _artist.Clear();
            UserProfile = null;
        }
    }
}
