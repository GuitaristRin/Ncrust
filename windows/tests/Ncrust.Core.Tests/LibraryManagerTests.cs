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

    [Fact]
    public async Task SubscribeAlbum_ReplacesAndPushes()
    {
        var cloud = new FakeCloud();
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
