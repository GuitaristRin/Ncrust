using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Cache;
using Ncrust.Core.Lyrics;
using Ncrust.Core.Platform;
using Ncrust.Core.Search;
using Xunit;

namespace Ncrust.Core.Tests;

public class LruCacheTests
{
    [Fact]
    public void EvictsLeastRecentlyUsed()
    {
        var cache = new LruCache<int, string>(2);
        cache.Put(1, "a");
        cache.Put(2, "b");
        Assert.True(cache.TryGet(1, out _)); // 1 becomes most recent
        cache.Put(3, "c");                   // evicts 2

        Assert.False(cache.TryGet(2, out _));
        Assert.True(cache.TryGet(1, out var a));
        Assert.Equal("a", a);
        Assert.Equal(2, cache.Count);
    }

    [Fact]
    public void Clear_Empties()
    {
        var cache = new LruCache<int, string>(2);
        cache.Put(1, "a");
        cache.Clear();
        Assert.Equal(0, cache.Count);
        Assert.False(cache.TryGet(1, out _));
    }
}

public class ContentCacheTests
{
    [Fact]
    public void HomeFreshness_TracksInjectedClock()
    {
        var now = 1_000L;
        var cache = new ContentCache(() => now);

        Assert.False(cache.IsHomeFresh());
        cache.MarkHomeWarmed();
        Assert.True(cache.IsHomeFresh());

        now += 15_000;
        Assert.False(cache.IsHomeFresh());
    }

    [Fact]
    public void AlbumLru_CapsAt32()
    {
        var cache = new ContentCache();
        var empty = new AlbumDetailResult(null, new List<SongItem>());
        for (var i = 0; i < 33; i++)
        {
            cache.PutAlbum(i, empty);
        }

        Assert.Null(cache.GetAlbum(0)); // 最早的被淘汰
        Assert.NotNull(cache.GetAlbum(32));
    }

    [Fact]
    public void ClearAll_ResetsEverything()
    {
        var cache = new ContentCache();
        cache.HomeDailySongs = new List<SongItem> { new SongItem { Id = 1 } };
        cache.PutAlbum(1, new AlbumDetailResult(null, new List<SongItem>()));

        cache.ClearAll();

        Assert.Null(cache.HomeDailySongs);
        Assert.Null(cache.GetAlbum(1));
    }
}

public class SearchHistoryTests
{
    [Fact]
    public async Task Add_PrependsDedupesAndCaps()
    {
        var now = 0L;
        var history = new SearchHistory(new InMemoryFileStore(), () => now);

        for (var i = 1; i <= 12; i++)
        {
            now = i;
            await history.AddSongAsync(Song(i));
        }

        var songs = await history.GetSongsAsync();
        Assert.Equal(SearchHistory.MaxItems, songs.Count);
        Assert.Equal(12, songs[0].Id);
        Assert.Equal(3, songs[^1].Id); // 1、2 被挤出

        now = 13;
        await history.AddSongAsync(Song(5)); // 去重并置顶
        var afterDedupe = await history.GetSongsAsync();
        Assert.Equal(5, afterDedupe[0].Id);
        Assert.Equal(songs.Count, afterDedupe.Count);
    }

    [Fact]
    public async Task AddSong_FillsTitleCoverAndSubtitle()
    {
        var history = new SearchHistory(new InMemoryFileStore());
        var song = new SongItem
        {
            Id = 7,
            Name = "歌名",
            Album = new AlbumRef { PicUrl = "cover" },
            Artists = new List<ArtistRef> { new ArtistRef { Name = "歌手" } },
        };

        await history.AddSongAsync(song);

        var item = (await history.GetSongsAsync())[0];
        Assert.Equal("歌名", item.Title);
        Assert.Equal("cover", item.CoverUrl);
        Assert.Equal("歌手", item.Subtitle);
    }

    [Fact]
    public async Task ExpiredEntries_AreDropped()
    {
        var now = 0L;
        var store = new InMemoryFileStore();
        var history = new SearchHistory(store, () => now);
        await history.AddSongAsync(Song(1));

        now = SearchHistory.TtlMs + 1;
        Assert.Empty(await history.GetSongsAsync());
    }

    [Fact]
    public async Task RemoveAndClear()
    {
        var history = new SearchHistory(new InMemoryFileStore());
        await history.AddSongAsync(Song(1));
        await history.AddSongAsync(Song(2));

        await history.RemoveAsync(SearchHistoryType.Song, 1);
        Assert.Equal(new long[] { 2 }, (await history.GetSongsAsync()).Select(i => i.Id));

        await history.ClearSectionAsync(SearchHistoryType.Song);
        Assert.Empty(await history.GetSongsAsync());
    }

    [Fact]
    public async Task PersistsAcrossInstances()
    {
        var store = new InMemoryFileStore();
        await new SearchHistory(store).AddAlbumAsync(new AlbumSearchItem { Id = 9, Name = "专辑" });

        var reloaded = await new SearchHistory(store).GetAlbumsAsync();

        Assert.Single(reloaded);
        Assert.Equal(9, reloaded[0].Id);
        Assert.Equal("专辑", reloaded[0].Title);
    }

    [Fact]
    public async Task CorruptFile_IsIgnored()
    {
        var store = new InMemoryFileStore();
        await store.WriteTextAsync("search_history.json", "{not json");

        Assert.Empty(await new SearchHistory(store).GetSongsAsync());
    }

    private static SongItem Song(long id) => new SongItem { Id = id, Name = "s" + id };
}

public class LyricsCacheTests
{
    [Fact]
    public async Task Clear_RemovesMemoryAndFile()
    {
        var store = new InMemoryFileStore();
        var cache = new LyricsCache(store);
        await cache.PutAsync(1, "lrc", "tlyric");

        await cache.ClearAsync();

        Assert.Null(await cache.GetAsync(1));
        Assert.Null(await new LyricsCache(store).GetAsync(1)); // 文件也已删除
    }

    [Fact]
    public async Task PutThenGet_RoundTrips()
    {
        var cache = new LyricsCache(new InMemoryFileStore());
        await cache.PutAsync(1, "lrc", "tlyric");

        var entry = await cache.GetAsync(1);

        Assert.NotNull(entry);
        Assert.Equal("lrc", entry!.Lrc);
        Assert.Equal("tlyric", entry.Translation);
        Assert.Null(await cache.GetAsync(2));
    }

    [Fact]
    public async Task PersistsAcrossInstances()
    {
        var store = new InMemoryFileStore();
        await new LyricsCache(store).PutAsync(5, "a", "b");

        var entry = await new LyricsCache(store).GetAsync(5);

        Assert.Equal("a", entry!.Lrc);
    }

    [Fact]
    public async Task EvictsOldestBeyondCapacity()
    {
        var now = 0L;
        var cache = new LyricsCache(new InMemoryFileStore(), () => now);
        for (var i = 1; i <= LyricsCache.MaxEntries + 1; i++)
        {
            now = i;
            await cache.PutAsync(i, "l" + i, string.Empty);
        }

        Assert.Null(await cache.GetAsync(1));
        Assert.NotNull(await cache.GetAsync(LyricsCache.MaxEntries + 1));
    }
}

internal sealed class InMemoryFileStore : IFileStore
{
    private readonly Dictionary<string, string> _files = new();

    public Task<string?> ReadTextAsync(string name) =>
        Task.FromResult(_files.TryGetValue(name, out var value) ? value : null);

    public Task WriteTextAsync(string name, string content)
    {
        _files[name] = content;
        return Task.CompletedTask;
    }

    public Task DeleteAsync(string name)
    {
        _files.Remove(name);
        return Task.CompletedTask;
    }
}
