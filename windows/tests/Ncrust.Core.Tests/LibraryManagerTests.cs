using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Library;
using Xunit;

namespace Ncrust.Core.Tests;

public class LibraryManagerTests
{
    [Fact]
    public async Task SaveSong_AddsLocallyAndClearsPendingAfterVerifiedLike()
    {
        var cloud = new FakeCloud();
        var manager = new LibraryManager(new InMemoryFileStore(), cloud, () => true);

        await manager.SaveSongAsync(Song(1));

        Assert.True(manager.IsSongSaved(1));
        Assert.False(manager.IsSongPendingSync(1));
        Assert.Contains((1L, true), cloud.LikeCalls);
    }

    [Fact]
    public async Task SaveSong_WhenLikeFails_KeepsPendingButSavedLocally()
    {
        var cloud = new FakeCloud { LikeOk = false };
        var manager = new LibraryManager(new InMemoryFileStore(), cloud, () => true);

        await manager.SaveSongAsync(Song(1));

        Assert.True(manager.IsSongSaved(1));
        Assert.True(manager.IsSongPendingSync(1));
    }

    [Fact]
    public async Task RemoveSong_RemovesAndUnlikes()
    {
        var cloud = new FakeCloud();
        var manager = new LibraryManager(new InMemoryFileStore(), cloud, () => true);
        await manager.SaveSongAsync(Song(1));

        await manager.RemoveSongAsync(1);

        Assert.False(manager.IsSongSaved(1));
        Assert.Contains((1L, false), cloud.LikeCalls);
    }

    [Fact]
    public async Task Refresh_ReplacesSongsAndLikedIds_WhenCloudConfirms()
    {
        var cloud = new FakeCloud();
        cloud.LikedIds.AddRange(new long[] { 10, 11 });
        cloud.Songs[10] = Song(10);
        cloud.Songs[11] = Song(11);
        cloud.Albums.Add(new CloudAlbum { AlbumId = 99, Name = "alb" });
        var manager = new LibraryManager(new InMemoryFileStore(), cloud, () => true);

        Assert.True(await manager.RefreshFromCloudAsync());

        Assert.Equal(new long[] { 10, 11 }, manager.GetSavedSongs().Select(s => s.Id));
        Assert.Equal(new long[] { 10, 11 }, manager.GetLikedSongIds());
        Assert.Equal(99, manager.GetSavedAlbums()[0].AlbumId);
    }

    [Fact]
    public async Task Refresh_LikedFailure_KeepsLocalSongs()
    {
        var cloud = new FakeCloud { LikedFailure = true, AlbumsThrow = true };
        var manager = new LibraryManager(new InMemoryFileStore(), cloud, () => true);
        await manager.SaveSongAsync(Song(1));

        Assert.False(await manager.RefreshFromCloudAsync());
        Assert.True(manager.IsSongSaved(1)); // 云端读失败不覆盖本地
    }

    [Fact]
    public async Task Refresh_ConfirmedEmpty_ClearsLocalSongs()
    {
        var cloud = new FakeCloud();
        var manager = new LibraryManager(new InMemoryFileStore(), cloud, () => true);
        await manager.SaveSongAsync(Song(1));
        cloud.LikedIds.Clear(); // 云端权威确认为空

        await manager.RefreshFromCloudAsync();

        Assert.Empty(manager.GetSavedSongs());
    }

    [Fact]
    public async Task Refresh_NotLoggedIn_ReturnsFalse()
    {
        var manager = new LibraryManager(new InMemoryFileStore(), new FakeCloud(), () => false);
        Assert.False(await manager.RefreshFromCloudAsync());
    }

    [Fact]
    public async Task LoadMoreLikedSongs_AppendsNextBatch()
    {
        var cloud = new FakeCloud();
        for (var i = 1; i <= 60; i++)
        {
            cloud.LikedIds.Add(i);
            cloud.Songs[i] = Song(i);
        }

        var manager = new LibraryManager(new InMemoryFileStore(), cloud, () => true);
        await manager.RefreshFromCloudAsync();
        Assert.Equal(50, manager.GetSavedSongs().Count);

        var more = await manager.LoadMoreLikedSongsAsync();

        Assert.Equal(10, more.Count);
        Assert.Equal(60, manager.GetSavedSongs().Count);
    }

    // ── 整列播放（issue #30）：全量以红心 id 表为准，不能只播已加载的那一页 ──────────────

    [Fact]
    public async Task LoadAllLikedSongs_ReturnsFullOrderedList()
    {
        var cloud = new FakeCloud();
        for (var i = 1; i <= 120; i++)
        {
            cloud.LikedIds.Add(i);
            cloud.Songs[i] = Song(i);
        }

        var manager = new LibraryManager(new InMemoryFileStore(), cloud, () => true);
        await manager.RefreshFromCloudAsync(); // 首屏只加载前 50 首详情

        var songs = await manager.LoadAllLikedSongsAsync();

        Assert.Equal(120, songs.Count);
        Assert.Equal(Enumerable.Range(1, 120).Select(i => (long)i), songs.Select(s => s.Id));

        // 分页缓存不被全量灌进去：loadMore 的切片按已加载数量算，灌满会让它永不补拉。
        Assert.Equal(50, manager.GetSavedSongs().Count);
    }

    [Fact]
    public async Task LoadAllLikedSongs_EmptyIdTable_FallsBackToLocal()
    {
        var cloud = new FakeCloud { LikeOk = false };
        var manager = new LibraryManager(new InMemoryFileStore(), cloud, () => false);
        await manager.SaveSongAsync(Song(7));

        var songs = await manager.LoadAllLikedSongsAsync();

        Assert.Equal(new long[] { 7 }, songs.Select(s => s.Id));
    }

    [Fact]
    public async Task AssembleLikedSongs_SkipsIdsWithoutDetail()
    {
        var ids = new long[] { 3, 1, 2 };
        var cached = new Dictionary<long, SongItem> { [1] = Song(1) };

        var songs = await LibraryManager.AssembleLikedSongsAsync(
            ids,
            cached,
            batchSize: 500,
            (batch, _) => Task.FromResult<IReadOnlyList<SongItem>>(new[] { Song(3) }), // 2 取不到详情
            CancellationToken.None);

        Assert.Equal(new long[] { 3, 1 }, songs.Select(s => s.Id));
    }

    [Fact]
    public async Task AssembleLikedSongs_UsesBatchesAndSkipsFailingBatch()
    {
        var ids = new long[] { 1, 2, 3, 4, 5 };
        var batches = new List<IReadOnlyList<long>>();

        var songs = await LibraryManager.AssembleLikedSongsAsync(
            ids,
            new Dictionary<long, SongItem>(),
            batchSize: 2,
            (batch, _) =>
            {
                batches.Add(batch);
                if (batch.Contains(3L))
                {
                    throw new Exception("batch boom"); // 单批失败只丢这一批（3、4 同批）
                }

                return Task.FromResult<IReadOnlyList<SongItem>>(batch.Select(id => Song(id)).ToList());
            },
            CancellationToken.None);

        Assert.Equal(new[] { 2, 2, 1 }, batches.Select(b => b.Count));
        Assert.Equal(new long[] { 1, 2, 5 }, songs.Select(s => s.Id));
    }

    [Fact]
    public async Task AssembleLikedSongs_DropsIdsNotRequested()
    {
        var ids = new long[] { 1, 2 };

        var songs = await LibraryManager.AssembleLikedSongsAsync(
            ids,
            new Dictionary<long, SongItem>(),
            batchSize: 500,
            // 接口异常时可能返回错序 / 多余的项：只认本批请求过的 id，不能让它们混进队列。
            (_, _) => Task.FromResult<IReadOnlyList<SongItem>>(new[] { Song(2), Song(99), Song(1) }),
            CancellationToken.None);

        Assert.Equal(new long[] { 1, 2 }, songs.Select(s => s.Id));
    }

    [Fact]
    public async Task AssembleLikedSongs_CachedHitsSkipNetwork()
    {
        var ids = new long[] { 1, 2 };
        var cached = new Dictionary<long, SongItem> { [1] = Song(1), [2] = Song(2) };
        var fetched = false;

        var songs = await LibraryManager.AssembleLikedSongsAsync(
            ids,
            cached,
            batchSize: 500,
            (_, _) =>
            {
                fetched = true;
                return Task.FromResult<IReadOnlyList<SongItem>>(Array.Empty<SongItem>());
            },
            CancellationToken.None);

        Assert.False(fetched);
        Assert.Equal(new long[] { 1, 2 }, songs.Select(s => s.Id));
    }

    [Fact]
    public async Task SubscribeAlbum_ReplacesAndPushes()
    {        var cloud = new FakeCloud();
        var manager = new LibraryManager(new InMemoryFileStore(), cloud, () => true);

        await manager.SubscribeAlbumAsync(new CloudAlbum { AlbumId = 5, Name = "a" });
        await manager.SubscribeAlbumAsync(new CloudAlbum { AlbumId = 5, Name = "a2" });

        Assert.Single(manager.GetSavedAlbums());
        Assert.Equal("a2", manager.GetSavedAlbums()[0].Name);
        Assert.Equal(new[] { (5L, true), (5L, true) }, cloud.SubCalls);
    }

    [Fact]
    public async Task PersistsAcrossInstances()
    {
        var store = new InMemoryFileStore();
        var cloud = new FakeCloud { LikeOk = false };
        var manager = new LibraryManager(store, cloud, () => true);
        await manager.SaveSongAsync(Song(7));

        var reloaded = new LibraryManager(store, cloud, () => true);
        await reloaded.PreloadAsync();

        Assert.True(reloaded.IsSongSaved(7));
        Assert.Equal("s7", reloaded.GetSavedSongs()[0].Name);
        Assert.True(reloaded.IsSongPendingSync(7));
    }

    /// <summary>权益随歌落盘：重启后「无版权」仍判得出来，而不是退化成「暂时无法播放」。</summary>
    [Fact]
    public async Task SavedSongs_KeepPrivilege()
    {
        var store = new InMemoryFileStore();
        var cloud = new FakeCloud { LikeOk = false };
        var manager = new LibraryManager(store, cloud, () => false);
        await manager.SaveSongAsync(new SongItem
        {
            Id = 5,
            Name = "s5",
            Privilege = new Ncrust.Core.Account.SongPrivilege { St = -200, Pl = 0, Fee = 1 },
        });

        var reloaded = new LibraryManager(store, cloud, () => false);
        await reloaded.PreloadAsync();

        var privilege = Assert.IsType<Ncrust.Core.Account.SongPrivilege>(reloaded.GetSavedSongs()[0].Privilege);
        Assert.Equal(-200, privilege.St);
        Assert.Equal(1, privilege.Fee);
    }

    private static SongItem Song(long id) => new SongItem { Id = id, Name = "s" + id };

    private sealed class FakeCloud : ILibraryCloud
    {
        public long UserId { get; set; } = 1;

        public List<long> LikedIds { get; } = new();

        public Dictionary<long, SongItem> Songs { get; } = new();

        public List<CloudAlbum> Albums { get; } = new();

        public bool LikeOk { get; set; } = true;

        public bool LikedFailure { get; set; }

        public bool AlbumsThrow { get; set; }

        public List<(long Id, bool Like)> LikeCalls { get; } = new();

        public List<(long Id, bool Sub)> SubCalls { get; } = new();

        public Task<long> GetCurrentUserIdAsync(CancellationToken cancellationToken) =>
            Task.FromResult(UserId);

        public Task<LikedIdsResult> GetLikedTrackIdsAsync(long uid, CancellationToken cancellationToken) =>
            Task.FromResult(LikedFailure
                ? LikedIdsResult.Failure("boom")
                : LikedIdsResult.Success(LikedIds.ToList()));

        public Task<IReadOnlyList<SongItem>> GetSongsByIdsAsync(
            IReadOnlyList<long> ids,
            CancellationToken cancellationToken) =>
            Task.FromResult((IReadOnlyList<SongItem>)ids
                .Where(Songs.ContainsKey)
                .Select(id => Songs[id])
                .ToList());

        public Task<IReadOnlyList<CloudAlbum>> GetSubscribedAlbumsAsync(CancellationToken cancellationToken) =>
            AlbumsThrow
                ? throw new System.Exception("albums boom")
                : Task.FromResult((IReadOnlyList<CloudAlbum>)Albums.ToList());

        public Task<bool> LikeSongAsync(long songId, bool like, CancellationToken cancellationToken)
        {
            LikeCalls.Add((songId, like));
            if (!LikeOk)
            {
                return Task.FromResult(false);
            }

            if (like)
            {
                if (!LikedIds.Contains(songId))
                {
                    LikedIds.Add(songId);
                }
            }
            else
            {
                LikedIds.Remove(songId);
            }

            return Task.FromResult(true);
        }

        public Task<bool> SubscribeAlbumAsync(long albumId, bool subscribe, CancellationToken cancellationToken)
        {
            SubCalls.Add((albumId, subscribe));
            return Task.FromResult(true);
        }
    }
}
