using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Json;
using Ncrust.Core.Net;
using Ncrust.Core.Platform;

namespace Ncrust.Core.Library
{
    /// <summary>红心歌单 id 读取结果：只有 Success 才允许覆盖本地。</summary>
    public sealed class LikedIdsResult
    {
        private LikedIdsResult(bool success, IReadOnlyList<long> ids, string failureReason)
        {
            IsSuccess = success;
            Ids = ids;
            FailureReason = failureReason;
        }

        public bool IsSuccess { get; }

        public IReadOnlyList<long> Ids { get; }

        public string FailureReason { get; }

        public static LikedIdsResult Success(IReadOnlyList<long> ids) => new LikedIdsResult(true, ids, string.Empty);

        public static LikedIdsResult Failure(string reason) => new LikedIdsResult(false, Array.Empty<long>(), reason);
    }

    /// <summary>LibraryManager 依赖的云操作，便于用假实现做单测；真实实现见 <see cref="ApiLibraryCloud"/>。</summary>
    public interface ILibraryCloud
    {
        Task<long> GetCurrentUserIdAsync(CancellationToken cancellationToken);

        Task<LikedIdsResult> GetLikedTrackIdsAsync(long uid, CancellationToken cancellationToken);

        Task<IReadOnlyList<SongItem>> GetSongsByIdsAsync(IReadOnlyList<long> ids, CancellationToken cancellationToken);

        Task<IReadOnlyList<CloudAlbum>> GetSubscribedAlbumsAsync(CancellationToken cancellationToken);

        Task<bool> LikeSongAsync(long songId, bool like, CancellationToken cancellationToken);

        Task<bool> SubscribeAlbumAsync(long albumId, bool subscribe, CancellationToken cancellationToken);
    }

    /// <summary>
    /// 云同步收藏库（对应 Android <c>LibraryManager.kt</c>）。
    ///
    /// 语义：收藏单曲先落本地即时生效，再异步推云端；只有云端**权威确认**（Success）才覆盖本地，
    /// Failure（网络 / 业务码 / 结构异常）一律保留本地，绝不把一次抖动折叠成空列表。
    /// 写操作未获云端确认（含读回校验不一致）时保留「待同步」标记，由 UI 显示「仅本地 / 待重试」。
    ///
    /// 持久化落 <see cref="IFileStore"/> 的 <c>library.json</c>。
    /// </summary>
    public sealed class LibraryManager
    {
        public const int LikedBatchSize = 50;

        private const string FileName = "library.json";

        private readonly IFileStore _files;
        private readonly ILibraryCloud _cloud;
        private readonly Func<bool> _isLoggedIn;
        private readonly object _lock = new object();

        private bool _loaded;
        private List<SongItem> _songs = new List<SongItem>();
        private List<CloudAlbum> _albums = new List<CloudAlbum>();
        private List<long> _likedIds = new List<long>();
        private Dictionary<long, bool> _pending = new Dictionary<long, bool>();

        public LibraryManager(IFileStore files, ILibraryCloud cloud, Func<bool>? isLoggedIn = null)
        {
            _files = files ?? throw new ArgumentNullException(nameof(files));
            _cloud = cloud ?? throw new ArgumentNullException(nameof(cloud));
            _isLoggedIn = isLoggedIn ?? (() => false);
        }

        public async Task PreloadAsync(CancellationToken cancellationToken = default) =>
            await EnsureLoadedAsync(cancellationToken).ConfigureAwait(false);

        public IReadOnlyList<SongItem> GetSavedSongs()
        {
            lock (_lock)
            {
                return _songs.ToList();
            }
        }

        public bool IsSongSaved(long songId)
        {
            lock (_lock)
            {
                return _songs.Any(song => song.Id == songId);
            }
        }

        public IReadOnlyList<SongItem> GetSongsByAlbumId(long albumId)
        {
            lock (_lock)
            {
                return _songs.Where(song => song.Album?.Id == albumId).ToList();
            }
        }

        public IReadOnlyList<CloudAlbum> GetSavedAlbums()
        {
            lock (_lock)
            {
                return _albums.ToList();
            }
        }

        public IReadOnlyList<long> GetLikedSongIds()
        {
            lock (_lock)
            {
                return _likedIds.ToList();
            }
        }

        public bool IsSongPendingSync(long songId)
        {
            lock (_lock)
            {
                return _pending.ContainsKey(songId);
            }
        }

        public IReadOnlyList<long> GetPendingSyncSongIds()
        {
            lock (_lock)
            {
                return _pending.Keys.ToList();
            }
        }

        public async Task SaveSongAsync(SongItem song, CancellationToken cancellationToken = default)
        {
            await EnsureLoadedAsync(cancellationToken).ConfigureAwait(false);
            bool added;
            lock (_lock)
            {
                added = _songs.All(existing => existing.Id != song.Id);
                if (added)
                {
                    _songs.Insert(0, song);
                }
            }

            if (!added)
            {
                return;
            }

            await PersistAsync(cancellationToken).ConfigureAwait(false);
            if (_isLoggedIn())
            {
                await PushLikeAsync(song.Id, true, cancellationToken).ConfigureAwait(false);
            }
        }

        public async Task SaveSongsAsync(IReadOnlyList<SongItem> songs, CancellationToken cancellationToken = default)
        {
            await EnsureLoadedAsync(cancellationToken).ConfigureAwait(false);
            var added = new List<SongItem>();
            lock (_lock)
            {
                foreach (var song in songs)
                {
                    if (_songs.All(existing => existing.Id != song.Id))
                    {
                        _songs.Insert(0, song);
                        added.Add(song);
                    }
                }
            }

            if (added.Count == 0)
            {
                return;
            }

            await PersistAsync(cancellationToken).ConfigureAwait(false);
            if (_isLoggedIn())
            {
                foreach (var song in added)
                {
                    await PushLikeAsync(song.Id, true, cancellationToken).ConfigureAwait(false);
                }
            }
        }

        public async Task RemoveSongAsync(long songId, CancellationToken cancellationToken = default)
        {
            await EnsureLoadedAsync(cancellationToken).ConfigureAwait(false);
            bool removed;
            lock (_lock)
            {
                removed = _songs.RemoveAll(song => song.Id == songId) > 0;
            }

            if (!removed)
            {
                return;
            }

            await PersistAsync(cancellationToken).ConfigureAwait(false);
            if (_isLoggedIn())
            {
                await PushLikeAsync(songId, false, cancellationToken).ConfigureAwait(false);
            }
        }

        public async Task SubscribeAlbumAsync(CloudAlbum album, CancellationToken cancellationToken = default)
        {
            await EnsureLoadedAsync(cancellationToken).ConfigureAwait(false);
            lock (_lock)
            {
                _albums.RemoveAll(existing => existing.AlbumId == album.AlbumId);
                _albums.Insert(0, album);
            }

            await PersistAsync(cancellationToken).ConfigureAwait(false);
            if (_isLoggedIn())
            {
                await _cloud.SubscribeAlbumAsync(album.AlbumId, true, cancellationToken).ConfigureAwait(false);
            }
        }

        public async Task RemoveAlbumAsync(long albumId, CancellationToken cancellationToken = default)
        {
            await EnsureLoadedAsync(cancellationToken).ConfigureAwait(false);
            bool removed;
            lock (_lock)
            {
                removed = _albums.RemoveAll(album => album.AlbumId == albumId) > 0;
            }

            if (!removed)
            {
                return;
            }

            await PersistAsync(cancellationToken).ConfigureAwait(false);
            if (_isLoggedIn())
            {
                await _cloud.SubscribeAlbumAsync(albumId, false, cancellationToken).ConfigureAwait(false);
            }
        }

        /// <summary>拉云端收藏刷新本地。未登录返回 false；单曲 / 专辑各自独立尝试。</summary>
        public async Task<bool> RefreshFromCloudAsync(CancellationToken cancellationToken = default)
        {
            await EnsureLoadedAsync(cancellationToken).ConfigureAwait(false);
            if (!_isLoggedIn())
            {
                return false;
            }

            long uid;
            try
            {
                uid = await _cloud.GetCurrentUserIdAsync(cancellationToken).ConfigureAwait(false);
            }
            catch
            {
                return false;
            }

            var anySuccess = false;

            var liked = await _cloud.GetLikedTrackIdsAsync(uid, cancellationToken).ConfigureAwait(false);
            if (liked.IsSuccess)
            {
                var ids = liked.Ids;
                lock (_lock)
                {
                    _likedIds = ids.ToList();
                }

                var firstBatch = ids.Count > LikedBatchSize
                    ? ids.Take(LikedBatchSize).ToList()
                    : ids.ToList();

                IReadOnlyList<SongItem> firstSongs;
                try
                {
                    firstSongs = firstBatch.Count > 0
                        ? await _cloud.GetSongsByIdsAsync(firstBatch, cancellationToken).ConfigureAwait(false)
                        : Array.Empty<SongItem>();
                }
                catch
                {
                    firstSongs = Array.Empty<SongItem>();
                }

                // 详情批量拉取失败时不能用空列表覆盖（firstBatch 非空却拿到空 = 异常）；
                // 云端确认「收藏为空」时才允许清空。
                if (firstBatch.Count == 0 || firstSongs.Count > 0)
                {
                    lock (_lock)
                    {
                        _songs = firstSongs.ToList();
                    }
                }

                ReconcilePending(ids);
                anySuccess = true;
            }

            try
            {
                var albums = await _cloud.GetSubscribedAlbumsAsync(cancellationToken).ConfigureAwait(false);
                lock (_lock)
                {
                    _albums = albums.ToList();
                }

                anySuccess = true;
            }
            catch
            {
                // 专辑读取失败不覆盖本地，也不影响单曲的结果。
            }

            if (anySuccess)
            {
                await PersistAsync(cancellationToken).ConfigureAwait(false);
            }

            return anySuccess;
        }

        /// <summary>滚动到底时分页拉取下一批收藏单曲详情。</summary>
        public async Task<IReadOnlyList<SongItem>> LoadMoreLikedSongsAsync(CancellationToken cancellationToken = default)
        {
            await EnsureLoadedAsync(cancellationToken).ConfigureAwait(false);
            List<long> allIds;
            int loaded;
            lock (_lock)
            {
                allIds = _likedIds.ToList();
                loaded = _songs.Count;
            }

            if (allIds.Count == 0 || loaded >= allIds.Count)
            {
                return Array.Empty<SongItem>();
            }

            var end = Math.Min(loaded + LikedBatchSize, allIds.Count);
            var slice = allIds.GetRange(loaded, end - loaded);

            IReadOnlyList<SongItem> fetched;
            try
            {
                fetched = await _cloud.GetSongsByIdsAsync(slice, cancellationToken).ConfigureAwait(false);
            }
            catch
            {
                return Array.Empty<SongItem>();
            }

            lock (_lock)
            {
                var seen = new HashSet<long>(_songs.Select(song => song.Id));
                foreach (var song in fetched)
                {
                    if (seen.Add(song.Id))
                    {
                        _songs.Add(song);
                    }
                }
            }

            await PersistAsync(cancellationToken).ConfigureAwait(false);
            return fetched;
        }

        /// <summary>重试所有待同步的点赞操作，方向依据当前本地收藏状态。</summary>
        public async Task RetryPendingSyncAsync(CancellationToken cancellationToken = default)
        {
            if (!_isLoggedIn())
            {
                return;
            }

            List<long> ids;
            lock (_lock)
            {
                ids = _pending.Keys.ToList();
            }

            foreach (var songId in ids)
            {
                bool desired;
                lock (_lock)
                {
                    desired = _songs.Any(song => song.Id == songId);
                }

                await PushLikeAsync(songId, desired, cancellationToken).ConfigureAwait(false);
            }
        }

        private async Task<bool> PushLikeAsync(long songId, bool like, CancellationToken cancellationToken)
        {
            MarkPending(songId, like);
            await PersistAsync(cancellationToken).ConfigureAwait(false);

            var ok = await _cloud.LikeSongAsync(songId, like, cancellationToken).ConfigureAwait(false);
            if (!ok)
            {
                return false;
            }

            var verified = await VerifyLikeAsync(songId, like, cancellationToken).ConfigureAwait(false);
            if (verified)
            {
                MarkPending(songId, null);
                await PersistAsync(cancellationToken).ConfigureAwait(false);
            }

            return verified;
        }

        private async Task<bool> VerifyLikeAsync(long songId, bool like, CancellationToken cancellationToken)
        {
            try
            {
                var uid = await _cloud.GetCurrentUserIdAsync(cancellationToken).ConfigureAwait(false);
                var result = await _cloud.GetLikedTrackIdsAsync(uid, cancellationToken).ConfigureAwait(false);
                return result.IsSuccess && (result.Ids.Contains(songId) == like);
            }
            catch
            {
                return false;
            }
        }

        private void MarkPending(long songId, bool? desired)
        {
            lock (_lock)
            {
                if (desired == null)
                {
                    _pending.Remove(songId);
                }
                else
                {
                    _pending[songId] = desired.Value;
                }
            }
        }

        private void ReconcilePending(IReadOnlyList<long> cloudLikedIds)
        {
            var cloudSet = new HashSet<long>(cloudLikedIds);
            lock (_lock)
            {
                foreach (var songId in _pending.Keys.ToList())
                {
                    if (_pending[songId] == cloudSet.Contains(songId))
                    {
                        _pending.Remove(songId);
                    }
                }
            }
        }

        private async Task EnsureLoadedAsync(CancellationToken cancellationToken)
        {
            if (_loaded)
            {
                return;
            }

            var raw = await _files.ReadTextAsync(FileName).ConfigureAwait(false);
            var (songs, albums, likedIds, pending) = Parse(raw);
            lock (_lock)
            {
                if (_loaded)
                {
                    return;
                }

                _songs = songs;
                _albums = albums;
                _likedIds = likedIds;
                _pending = pending;
                _loaded = true;
            }
        }

        private Task PersistAsync(CancellationToken cancellationToken)
        {
            string json;
            lock (_lock)
            {
                var sb = new StringBuilder();
                sb.Append("{\"songs\":[");
                for (var i = 0; i < _songs.Count; i++)
                {
                    if (i > 0)
                    {
                        sb.Append(',');
                    }

                    AppendSong(sb, _songs[i]);
                }

                sb.Append("],\"albums\":[");
                for (var i = 0; i < _albums.Count; i++)
                {
                    if (i > 0)
                    {
                        sb.Append(',');
                    }

                    AppendAlbum(sb, _albums[i]);
                }

                sb.Append("],\"likedIds\":[");
                for (var i = 0; i < _likedIds.Count; i++)
                {
                    if (i > 0)
                    {
                        sb.Append(',');
                    }

                    sb.Append(_likedIds[i]);
                }

                sb.Append("],\"pending\":{");
                var firstPending = true;
                foreach (var pair in _pending)
                {
                    if (!firstPending)
                    {
                        sb.Append(',');
                    }

                    firstPending = false;
                    sb.Append('"').Append(pair.Key).Append("\":").Append(pair.Value ? "true" : "false");
                }

                sb.Append("}}");
                json = sb.ToString();
            }

            return _files.WriteTextAsync(FileName, json);
        }

        private static void AppendSong(StringBuilder sb, SongItem song)
        {
            sb.Append("{\"id\":").Append(song.Id).Append(",\"name\":").Append(JsonText.Escape(song.Name)).Append(",\"ar\":[");
            for (var i = 0; i < song.Artists.Count; i++)
            {
                if (i > 0)
                {
                    sb.Append(',');
                }

                var artist = song.Artists[i];
                sb.Append("{\"name\":").Append(JsonText.Escape(artist.Name));
                if (artist.Id.HasValue)
                {
                    sb.Append(",\"id\":").Append(artist.Id.Value);
                }

                sb.Append('}');
            }

            sb.Append(']');
            if (song.Album != null)
            {
                sb.Append(",\"al\":{\"name\":").Append(JsonText.Escape(song.Album.Name));
                if (song.Album.Id.HasValue)
                {
                    sb.Append(",\"id\":").Append(song.Album.Id.Value);
                }

                if (!string.IsNullOrEmpty(song.Album.PicUrl))
                {
                    sb.Append(",\"picUrl\":").Append(JsonText.Escape(song.Album.PicUrl));
                }

                sb.Append('}');
            }

            if (song.Duration > 0)
            {
                sb.Append(",\"dt\":").Append(song.Duration);
            }

            sb.Append('}');
        }

        private static void AppendAlbum(StringBuilder sb, CloudAlbum album)
        {
            sb.Append("{\"albumId\":").Append(album.AlbumId)
                .Append(",\"name\":").Append(JsonText.Escape(album.Name))
                .Append(",\"artist\":").Append(JsonText.Escape(album.Artist))
                .Append(",\"picUrl\":").Append(JsonText.Escape(album.PicUrl))
                .Append(",\"songCount\":").Append(album.SongCount)
                .Append('}');
        }

        private static (List<SongItem>, List<CloudAlbum>, List<long>, Dictionary<long, bool>) Parse(string? raw)
        {
            var songs = new List<SongItem>();
            var albums = new List<CloudAlbum>();
            var likedIds = new List<long>();
            var pending = new Dictionary<long, bool>();

            if (string.IsNullOrEmpty(raw))
            {
                return (songs, albums, likedIds, pending);
            }

            JsonValue json;
            try
            {
                json = JsonValue.Parse(raw!);
            }
            catch (JsonParseException)
            {
                return (songs, albums, likedIds, pending);
            }

            var songArray = json.GetArray("songs");
            if (songArray != null)
            {
                foreach (var item in songArray)
                {
                    songs.Add(SongItem.FromJson(item));
                }
            }

            var albumArray = json.GetArray("albums");
            if (albumArray != null)
            {
                foreach (var item in albumArray)
                {
                    albums.Add(new CloudAlbum
                    {
                        AlbumId = item.GetLong("albumId"),
                        Name = item.GetString("name", string.Empty) ?? string.Empty,
                        Artist = item.GetString("artist", string.Empty) ?? string.Empty,
                        PicUrl = item.GetString("picUrl", string.Empty) ?? string.Empty,
                        SongCount = item.GetInt("songCount"),
                    });
                }
            }

            var idArray = json.GetArray("likedIds");
            if (idArray != null)
            {
                foreach (var item in idArray)
                {
                    likedIds.Add(item.LongValue);
                }
            }

            var pendingJson = json.GetObject("pending");
            if (pendingJson != null)
            {
                foreach (var member in pendingJson.Members)
                {
                    if (long.TryParse(member.Key, out var id))
                    {
                        pending[id] = member.Value.BoolValue;
                    }
                }
            }

            return (songs, albums, likedIds, pending);
        }
    }
}
