using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Platform;
using Ncrust.Core.Playback;
using Xunit;

namespace Ncrust.Core.Tests;

public class PlaybackQueuePeekTests
{
    [Fact]
    public void Cycle_WrapsAtTail_AndNullForSingleItem()
    {
        var queue = Queue(PlaybackMode.Cycle, 0, 1, 2, 3);

        queue.SetCurrentIndex(1);
        Assert.Equal(2, queue.PeekNext()!.Id);
        queue.SetCurrentIndex(3);
        Assert.Equal(0, queue.PeekNext()!.Id); // 队尾回绕

        var single = Queue(PlaybackMode.Cycle, 9);
        Assert.Null(single.PeekNext());
    }

    [Fact]
    public void Single_NextIsCurrent()
    {
        var queue = Queue(PlaybackMode.Single, 1, 2, 3);
        queue.SetCurrentIndex(1);
        Assert.Equal(2, queue.PeekNext()!.Id);
        Assert.Equal(2, queue.PeekPrevious()!.Id);
    }

    [Fact]
    public void LineAndInfinity_StopAtTail()
    {
        var line = Queue(PlaybackMode.Line, 1, 2);
        line.SetCurrentIndex(1);
        Assert.Null(line.PeekNext());

        var infinity = Queue(PlaybackMode.Infinity, 1, 2);
        infinity.SetCurrentIndex(1);
        Assert.Null(infinity.PeekNext());
    }

    [Fact]
    public void Shuffle_NextIsNotCurrent_AndPreviousNullAtStart()
    {
        var queue = Queue(PlaybackMode.Shuffle, 1, 2, 3, 4);

        Assert.NotNull(queue.PeekNext());
        Assert.NotEqual(1, queue.PeekNext()!.Id);
        Assert.Null(queue.PeekPrevious());
    }

    [Fact]
    public void Previous_WrapsForCycle_AndNullForInfinity()
    {
        var cycle = Queue(PlaybackMode.Cycle, 1, 2, 3);
        Assert.Null(Queue(PlaybackMode.Infinity, 1, 2).PeekPrevious());
        cycle.SetCurrentIndex(0);
        Assert.Equal(3, cycle.PeekPrevious()!.Id);
    }

    [Theory]
    [InlineData(PlaybackMode.Cycle)]
    [InlineData(PlaybackMode.Shuffle)]
    [InlineData(PlaybackMode.Single)]
    [InlineData(PlaybackMode.Line)]
    public void MoveNext_LandsOnWhatPeekNextPredicted(PlaybackMode mode)
    {
        var queue = Queue(mode, 1, 2, 3, 4, 5);
        for (var step = 0; step < 12; step++)
        {
            var predicted = queue.PeekNext();
            var moved = queue.MoveNext();
            Assert.Equal(predicted?.Id, moved?.Id);
            if (moved != null)
            {
                Assert.Equal(moved.Id, queue.Current!.Id); // 不变量：Songs[CurrentIndex] 就是新当前曲
            }
        }
    }

    [Fact]
    public void Shuffle_MoveNext_VisitsEverySongOncePerRound()
    {
        // 回归：只改 CurrentIndex 不推进打乱位置时，PeekNext 会一直指向同一首。
        var queue = Queue(PlaybackMode.Shuffle, 1, 2, 3, 4, 5);
        var played = new List<long> { queue.Current!.Id };
        for (var i = 0; i < 4; i++)
        {
            played.Add(queue.MoveNext()!.Id);
        }

        Assert.Equal(new long[] { 1, 2, 3, 4, 5 }, played.OrderBy(id => id));
    }

    [Fact]
    public void Shuffle_MovePrevious_RetracesTheRound()
    {
        var queue = Queue(PlaybackMode.Shuffle, 1, 2, 3, 4);
        var first = queue.Current!.Id;
        var second = queue.MoveNext()!.Id;
        queue.MoveNext();

        Assert.Equal(second, queue.MovePrevious()!.Id);
        Assert.Equal(first, queue.MovePrevious()!.Id);
        Assert.Null(queue.MovePrevious()); // 一轮开头不可回退，状态不变
        Assert.Equal(first, queue.Current!.Id);
    }

    [Fact]
    public void Line_MoveNext_AtTail_ReturnsNullAndKeepsCurrent()
    {
        var queue = Queue(PlaybackMode.Line, 1, 2);
        queue.SetCurrentIndex(1);
        Assert.Null(queue.MoveNext());
        Assert.Equal(2, queue.Current!.Id);
    }

    [Fact]
    public void Cycle_MovePrevious_WrapsToTail()
    {
        var queue = Queue(PlaybackMode.Cycle, 1, 2, 3);
        Assert.Equal(3, queue.MovePrevious()!.Id);
        Assert.Null(Queue(PlaybackMode.Infinity, 1, 2).MovePrevious());
    }

    private static PlaybackQueue Queue(PlaybackMode mode, params long[] ids)
    {
        var queue = new PlaybackQueue();
        queue.ReplaceAll(ids.Select(id => new SongItem { Id = id, Name = "s" + id }).ToList());
        queue.SetMode(mode);
        return queue;
    }
}

public class PlaybackPreferencesTests
{
    [Fact]
    public void Defaults_MatchAndroid()
    {
        var prefs = new PlaybackPreferences(new FakeSettings());

        Assert.True(prefs.GaplessEnabled);
        Assert.True(prefs.LyricsTranslation);
        Assert.Equal(3, prefs.WifiQualityIndex);
        Assert.Equal(1, prefs.MobileQualityIndex);
        Assert.Equal("lossless", prefs.QualityForNetwork(metered: false));
        Assert.Equal("higher", prefs.QualityForNetwork(metered: true));
    }

    [Fact]
    public void SetAndGet_RoundTrips()
    {
        var prefs = new PlaybackPreferences(new FakeSettings());
        prefs.GaplessEnabled = false;
        prefs.MobileQualityIndex = 6;

        Assert.False(prefs.GaplessEnabled);
        Assert.Equal("dolby", prefs.QualityForNetwork(metered: true));
    }
}

public class PlaybackStateStoreTests
{
    [Fact]
    public async Task SaveLoad_RoundTrips()
    {
        var store = new PlaybackStateStore(new InMemoryFileStore());
        var queue = new List<SongItem>
        {
            new SongItem { Id = 1, Name = "one" },
            new SongItem { Id = 2, Name = "two", Artists = new List<ArtistRef> { new ArtistRef { Name = "a" } } },
        };

        await store.SaveAsync(queue, 1);
        var loaded = await store.LoadAsync();

        Assert.NotNull(loaded);
        Assert.Equal(1, loaded!.Index);
        Assert.Equal(new long[] { 1, 2 }, loaded.Queue.Select(s => s.Id));
        Assert.Equal("two", loaded.Queue[1].Name);
        Assert.Equal("a", loaded.Queue[1].Artists[0].Name);
    }

    [Fact]
    public async Task Load_MissingOrCorrupt_ReturnsNull()
    {
        var store = new PlaybackStateStore(new InMemoryFileStore());
        Assert.Null(await store.LoadAsync());

        var files = new InMemoryFileStore();
        await files.WriteTextAsync("playback_state.json", "{not json");
        Assert.Null(await new PlaybackStateStore(files).LoadAsync());
    }
}

public class PlaybackSessionStateTests
{
    [Fact]
    public void PreloadCandidate_OnlyInsideWindow()
    {
        var settings = new FakeSettings();
        var session = new PlaybackSessionState(
            Queue(1, 2, 3), new PlaybackPreferences(settings), new PlaybackStateStore(new InMemoryFileStore()));

        session.UpdateProgress(30_000, 100_000); // 还剩 70s
        Assert.Null(session.PreloadCandidate());

        session.UpdateProgress(50_000, 100_000); // 还剩 50s
        Assert.Equal(2, session.PreloadCandidate()!.Id);

        settings.SetBool("gapless_playback", false);
        Assert.Null(session.PreloadCandidate());
    }

    [Fact]
    public void PreloadCandidate_IgnoresTooEarly()
    {
        var session = new PlaybackSessionState(
            Queue(1, 2), new PlaybackPreferences(new FakeSettings()), new PlaybackStateStore(new InMemoryFileStore()));

        session.UpdateProgress(500, 5_000); // 位置 <= 1s
        Assert.Null(session.PreloadCandidate());
    }

    [Fact]
    public async Task Restore_AppliesQueueAndIndex()
    {
        var store = new PlaybackStateStore(new InMemoryFileStore());
        await store.SaveAsync(new List<SongItem> { new SongItem { Id = 1 }, new SongItem { Id = 2 }, new SongItem { Id = 3 } }, 1);

        var queue = new PlaybackQueue();
        var session = new PlaybackSessionState(queue, new PlaybackPreferences(new FakeSettings()), store);
        await session.RestoreAsync();

        Assert.Equal(3, queue.Songs.Count);
        Assert.Equal(2, queue.Current!.Id);
    }

    private static PlaybackQueue Queue(params long[] ids)
    {
        var queue = new PlaybackQueue();
        queue.ReplaceAll(ids.Select(id => new SongItem { Id = id, Name = "s" + id }).ToList());
        return queue;
    }
}

internal sealed class FakeSettings : ISettingsStore
{
    private readonly Dictionary<string, int> _ints = new();
    private readonly Dictionary<string, bool> _bools = new();
    private readonly Dictionary<string, string> _strings = new();

    public int GetInt(string key, int fallback) => _ints.TryGetValue(key, out var value) ? value : fallback;

    public void SetInt(string key, int value) => _ints[key] = value;

    public bool GetBool(string key, bool fallback) => _bools.TryGetValue(key, out var value) ? value : fallback;

    public void SetBool(string key, bool value) => _bools[key] = value;

    public string? GetString(string key) => _strings.TryGetValue(key, out var value) ? value : null;

    public void SetString(string key, string? value)
    {
        if (value == null)
        {
            _strings.Remove(key);
        }
        else
        {
            _strings[key] = value;
        }
    }
}
