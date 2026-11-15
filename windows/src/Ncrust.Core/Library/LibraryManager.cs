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

        /// <summary>
        /// 整列播放补详情时的分批上界：<c>song/detail</c> 的 <c>c</c> 数组过长会被网关拒掉（414 / 超时），
        /// 500 是留了余量的可用上界。与首屏分页的 <see cref="LikedBatchSize"/> = 50 不是一回事，
        /// 后者是 UI 每屏渲染量，别拿它当网络批次（同 Android <c>LIKED_FETCH_BATCH_SIZE</c>）。
        /// </summary>
        internal const int LikedFetchBatchSize = 500;

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

        // 每次收藏意图的身份（参 Android PendingLikeWrites）：写入按序执行，排队时已被新意图替代的
        // 请求不发送，旧请求的读回也不能清掉新意图。_revision 在记录 / 清除意图时递增，
        // 刷新据此丢弃读取期间发生过本地变更的快照。
        private readonly Dictionary<long, long> _intentRevision = new Dictionary<long, long>();
        private readonly SemaphoreSlim _writeLock = new SemaphoreSlim(1, 1);
        private long _revision;

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
            var push = _isLoggedIn();
            long intent = 0;
            bool added;
            lock (_lock)
            {
                added = _songs.All(existing => existing.Id != song.Id);
                if (added)
                {
                    _songs.Insert(0, song);
                    if (push)
                    {
                        intent = RecordIntentLocked(song.Id, true);
                    }
                }
            }

            if (!added)
            {
                return;
            }

            // 意图先落盘再调度网络（同 Android）：进程被杀也不丢待同步。
            await PersistAsync(cancellationToken).ConfigureAwait(false);
            if (push)
            {
                await SubmitLikeAsync(song.Id, true, intent, cancellationToken).ConfigureAwait(false);
            }
        }

        public async Task SaveSongsAsync(IReadOnlyList<SongItem> songs, CancellationToken cancellationToken = default)
        {
            await EnsureLoadedAsync(cancellationToken).ConfigureAwait(false);
            var push = _isLoggedIn();
            var added = new List<(long Id, long Intent)>();
            lock (_lock)
            {
                foreach (var song in songs)
                {
                    if (_songs.All(existing => existing.Id != song.Id))
                    {
                        _songs.Insert(0, song);
                        added.Add((song.Id, push ? RecordIntentLocked(song.Id, true) : 0));
                    }
                }
            }

            if (added.Count == 0)
            {
                return;
            }

            await PersistAsync(cancellationToken).ConfigureAwait(false);
            if (push)
            {
                foreach (var item in added)
                {
                    await SubmitLikeAsync(item.Id, true, item.Intent, cancellationToken).ConfigureAwait(false);
                }
            }
        }

        public async Task RemoveSongAsync(long songId, CancellationToken cancellationToken = default)
        {
            await EnsureLoadedAsync(cancellationToken).ConfigureAwait(false);
            var push = _isLoggedIn();
            long intent = 0;
            bool removed;
            lock (_lock)
            {
                removed = _songs.RemoveAll(song => song.Id == songId) > 0;
                if (removed)
                {
                    if (push)
                    {
                        intent = RecordIntentLocked(songId, false);
                    }
                }
            }

            if (!removed)
            {
                return;
            }

            await PersistAsync(cancellationToken).ConfigureAwait(false);
            if (push)
            {
                await SubmitLikeAsync(songId, false, intent, cancellationToken).ConfigureAwait(false);
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

            long refreshRevision;
            lock (_lock)
            {
                refreshRevision = _revision;
            }

            var liked = await _cloud.GetLikedTrackIdsAsync(uid, cancellationToken).ConfigureAwait(false);
            if (liked.IsSuccess)
            {
                Dictionary<long, bool> pendingAtRead;
                lock (_lock)
                {
                    pendingAtRead = new Dictionary<long, bool>(_pending);
                }

                // 待确认的收藏 / 取消照本地意图显示：读延迟不能抹掉待同步项，也不能恢复待取消项。
                var ids = MergePendingLikeIds(liked.Ids, pendingAtRead);
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
                lock (_lock)
                {
                    // 读取期间发生过本地变更（新意图或写入确认）：这份快照已过时，保留更新的本地状态。
                    if (_revision == refreshRevision)
                    {
                        if (firstBatch.Count == 0 || firstSongs.Count > 0)
                        {
                            var retained = _songs.Where(song => _pending.TryGetValue(song.Id, out var desired) && desired);
                            var seen = new HashSet<long>();
                            _songs = retained.Concat(firstSongs)
                                .Where(song => seen.Add(song.Id))
                                .Where(song => !_pending.TryGetValue(song.Id, out var desired) || desired)
                                .ToList();
                        }

                        _likedIds = MergePendingLikeIds(liked.Ids, _pending);
                    }
                }

                // 普通刷新不能确认在途写入（可能读到写入前的状态），故不清待同步；
                // 只有串行写任务自己的读回才清除与其身份一致的意图。
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

        /// <summary>
        /// 「我喜欢的音乐」全量详情，供「全部播放 / 插播全部 / 追加全部」用（issue #30：原先只播已加载的那一页）。
        ///
        /// 顺序以红心 id 表（<see cref="GetLikedSongIds"/>）为准 —— 它才是全量且有序的底表；
        /// 本地 <c>_songs</c> 只是已分页加载的前若干首，当命中源用。
        ///
        /// 刻意**不把全量写回** <c>_songs</c>：那是 UI 的分页状态，灌进全量会让
        /// <see cref="LoadMoreLikedSongsAsync"/> 的切片错位（它按 <c>_songs.Count</c> 切片，
        /// 会以为后面几批已经加载过而永不补拉）。同 Android <c>loadAllLikedSongs</c>。
        ///
        /// id 表为空（未登录 / 未同步）时退回本地列表。某批取不到时只丢那一批，
        /// 调用方用 <c>result.Count &lt; GetLikedSongIds().Count</c> 判断要不要提示「部分未加载」。
        /// </summary>
        public async Task<IReadOnlyList<SongItem>> LoadAllLikedSongsAsync(CancellationToken cancellationToken = default)
        {
            await EnsureLoadedAsync(cancellationToken).ConfigureAwait(false);
            List<long> ids;
            Dictionary<long, SongItem> cached;
            lock (_lock)
            {
                ids = _likedIds.ToList();
                cached = _songs.ToDictionary(song => song.Id, song => song);
            }

            if (ids.Count == 0)
            {
                return GetSavedSongs();
            }

            return await AssembleLikedSongsAsync(ids, cached, LikedFetchBatchSize, FetchBatchAsync, cancellationToken)
                .ConfigureAwait(false);
        }

        /// <summary>
        /// 按 id 表顺序拼装单曲：命中 <paramref name="cached"/> 的直接取，缺失的按
        /// <paramref name="batchSize"/> 分批交给 <paramref name="fetchBatch"/>，取不到详情的 id
        /// 直接跳过（不占位、不编数据）。单批抛异常只丢那一批，其余照常返回。
        ///
        /// 纯函数（不碰实例状态）以便单测锁住保序、分批与缓存命中。取消异常原样抛出。
        /// </summary>
        internal static async Task<IReadOnlyList<SongItem>> AssembleLikedSongsAsync(
            IReadOnlyList<long> ids,
            IReadOnlyDictionary<long, SongItem> cached,
            int batchSize,
            Func<IReadOnlyList<long>, CancellationToken, Task<IReadOnlyList<SongItem>>> fetchBatch,
            CancellationToken cancellationToken)
        {
            var missing = new List<long>();
            foreach (var id in ids)
            {
                if (!cached.ContainsKey(id))
                {
                    missing.Add(id);
                }
            }

            var fetched = new Dictionary<long, SongItem>();
            var size = Math.Max(1, batchSize);
            for (var start = 0; start < missing.Count; start += size)
            {
                var batch = missing.GetRange(start, Math.Min(size, missing.Count - start));
                IReadOnlyList<SongItem> songs;
                try
                {
                    songs = await fetchBatch(batch, cancellationToken).ConfigureAwait(false);
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    throw;
                }
                catch
                {
                    continue; // 单批失败只丢这一批。
                }

                // 只认本批请求过的 id：接口异常时可能返回错序 / 多余的项，不能让它们混进队列。
                var wanted = new HashSet<long>(batch);
                foreach (var song in songs)
                {
                    if (wanted.Contains(song.Id))
                    {
                        fetched[song.Id] = song;
                    }
                }
            }

            var result = new List<SongItem>(ids.Count);
            foreach (var id in ids)
            {
                if (cached.TryGetValue(id, out var cachedSong))
                {
                    result.Add(cachedSong);
                }
                else if (fetched.TryGetValue(id, out var fetchedSong))
                {
                    result.Add(fetchedSong);
                }
            }

            return result;
        }

        private Task<IReadOnlyList<SongItem>> FetchBatchAsync(
            IReadOnlyList<long> ids,
            CancellationToken cancellationToken) =>
            _cloud.GetSongsByIdsAsync(ids, cancellationToken);

        /// <summary>
        /// 重试所有待同步的点赞操作。期望方向**只取待同步表记下的那一个**，不能拿 <see cref="IsSongSaved"/>
        /// 反推：首屏之后未加载详情的收藏不在 <c>_songs</c> 里，反推会把待收藏误发成取消（同 Android
        /// <c>planPendingLikeRetries</c>）。
        /// </summary>
        public async Task RetryPendingSyncAsync(CancellationToken cancellationToken = default)
        {
            await EnsureLoadedAsync(cancellationToken).ConfigureAwait(false);
            if (!_isLoggedIn())
            {
                return;
            }

            List<KeyValuePair<long, bool>> plans;
            lock (_lock)
            {
                plans = _pending.ToList();
            }

            foreach (var plan in plans)
            {
                long intent;
                lock (_lock)
                {
                    // 计划之后用户又改了方向：以新意图为准，这一条不再重试。
                    if (!_pending.TryGetValue(plan.Key, out var desired) || desired != plan.Value)
                    {
                        continue;
                    }

                    intent = RecordIntentLocked(plan.Key, plan.Value);
                }

                await SubmitLikeAsync(plan.Key, plan.Value, intent, cancellationToken).ConfigureAwait(false);
            }
        }

        /// <summary>待确认收藏排在前面，待取消的剔除（同 Android <c>mergePendingLikeIds</c>）。</summary>
        internal static List<long> MergePendingLikeIds(IReadOnlyList<long> cloud, IReadOnlyDictionary<long, bool> pending)
        {
            var seen = new HashSet<long>();
            var result = new List<long>(cloud.Count + pending.Count);
            foreach (var pair in pending)
            {
                if (pair.Value && seen.Add(pair.Key))
                {
                    result.Add(pair.Key);
                }
            }

            foreach (var id in cloud)
            {
                if (seen.Add(id) && !(pending.TryGetValue(id, out var desired) && !desired))
                {
                    result.Add(id);
                }
            }

            return result;
        }

        private long RecordIntentLocked(long songId, bool like)
        {
            _pending[songId] = like;
            var intent = ++_revision;
            _intentRevision[songId] = intent;
            return intent;
        }

        private bool IsCurrentIntentLocked(long songId, long intent) =>
            _pending.ContainsKey(songId) &&
            _intentRevision.TryGetValue(songId, out var current) &&
            current == intent;

        private bool IsCurrentIntent(long songId, long intent)
        {
            lock (_lock)
            {
                return IsCurrentIntentLocked(songId, intent);
            }
        }

        /// <summary>
        /// 串行提交一次收藏意图。排队时已被替代的意图不发送；写入或读回期间被替代的，结果不清除新意图。
        /// 写入未获确认一律保留待同步——不重放、不回退旧通道（参 spec/api/xeapi.md §6）。
        /// </summary>
        private async Task<bool> SubmitLikeAsync(long songId, bool like, long intent, CancellationToken cancellationToken)
        {
            await _writeLock.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                if (!IsCurrentIntent(songId, intent))
                {
                    return false;
                }

                var ok = await _cloud.LikeSongAsync(songId, like, cancellationToken).ConfigureAwait(false);
                if (!ok || !IsCurrentIntent(songId, intent))
                {
                    return false;
                }

                if (!await VerifyLikeAsync(songId, like, cancellationToken).ConfigureAwait(false))
                {
                    return false;
                }

                bool cleared;
                lock (_lock)
                {
                    cleared = IsCurrentIntentLocked(songId, intent);
                    if (cleared)
                    {
                        _pending.Remove(songId);
                        _intentRevision.Remove(songId);
                        _revision++;
                    }
                }

                if (cleared)
                {
                    await PersistAsync(cancellationToken).ConfigureAwait(false);
                }

                return cleared;
            }
            finally
            {
                _writeLock.Release();
            }
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
                sb.Append("{\"songs\":").Append(SongJson.Write(_songs)).Append(",\"albums\":[");
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

            songs.AddRange(SongJson.ReadArray(json.GetArray("songs")));

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
