using System.Text.Json;
using Ncrust.Core.Api;
using Ncrust.Core.Playback;
using Xunit;

namespace Ncrust.Core.Tests;

/// <summary>spec/fixtures/queue 的逐条验证。</summary>
public class PlaybackQueueTests
{
    public static IEnumerable<object[]> Cases() =>
        Spec.LoadJson("fixtures", "queue", "queue.json")
            .RootElement
            .EnumerateArray()
            .Select(e => new object[] { e.GetProperty("id").GetString()! })
            .ToList();

    [Theory]
    [MemberData(nameof(Cases))]
    public void MatchesFixture(string id)
    {
        var fixture = Load(id);
        var input = fixture.GetProperty("input");
        var expect = fixture.GetProperty("expect");

        var queue = new PlaybackQueue();
        queue.ReplaceAll(Songs(input.GetProperty("queue")));
        queue.SetMode(Enum.Parse<PlaybackMode>(input.GetProperty("mode").GetString()!, ignoreCase: true));
        var startCurrent = input.GetProperty("current").GetInt32();
        if (startCurrent >= 0)
        {
            queue.SetCurrentIndex(startCurrent);
        }

        Apply(queue, input.GetProperty("op"));

        Assert.Equal(ExpectedSongs(expect), queue.Songs.Select(s => s.Id).ToArray());
        Assert.Equal(expect.GetProperty("current").GetInt32(), queue.CurrentIndex);

        if (expect.TryGetProperty("shuffleValid", out var valid) && valid.GetBoolean())
        {
            var expectedIndices = Enumerable.Range(0, queue.Songs.Count).ToHashSet();
            Assert.Equal(queue.Songs.Count, queue.ShuffledIndices.Count);
            Assert.True(expectedIndices.SetEquals(queue.ShuffledIndices), "打乱表不是 0..n-1 的排列");
            Assert.Equal(queue.CurrentIndex, queue.ShuffledIndices[0]);
            Assert.Equal(0, queue.ShuffledPosition);
        }
    }

    [Fact]
    public void EmptyQueue_HasNoCurrent()
    {
        var queue = new PlaybackQueue();
        Assert.Equal(-1, queue.CurrentIndex);
        Assert.Null(queue.Current);
    }

    private static void Apply(PlaybackQueue queue, JsonElement op)
    {
        switch (op.GetProperty("type").GetString())
        {
            case "insertNext":
                queue.InsertNext(Song(op.GetProperty("id").GetInt64()));
                break;
            case "append":
                queue.Append(Song(op.GetProperty("id").GetInt64()));
                break;
            case "playSong":
                queue.PlaySong(Song(op.GetProperty("id").GetInt64()));
                break;
            case "insertAllNext":
                queue.InsertAllNext(Songs(op.GetProperty("ids")));
                break;
            case "appendAll":
                queue.AppendAll(Songs(op.GetProperty("ids")));
                break;
            case "remove":
                queue.RemoveAt(op.GetProperty("index").GetInt32());
                break;
            case "move":
                queue.Move(op.GetProperty("from").GetInt32(), op.GetProperty("to").GetInt32());
                break;
            case "playFrom":
                queue.SetCurrentIndex(op.GetProperty("index").GetInt32());
                break;
            case "replace":
                queue.ReplaceAll(Songs(op.GetProperty("ids")));
                break;
            default:
                throw new InvalidOperationException($"未知 op {op.GetProperty("type").GetString()}");
        }
    }

    private static List<SongItem> Songs(JsonElement ids) =>
        ids.EnumerateArray().Select(e => Song(e.GetInt64())).ToList();

    private static SongItem Song(long id) => new SongItem { Id = id };

    private static long[] ExpectedSongs(JsonElement expect) =>
        expect.GetProperty("queue").EnumerateArray().Select(e => e.GetInt64()).ToArray();

    private static JsonElement Load(string id)
    {
        using var document = Spec.LoadJson("fixtures", "queue", "queue.json");
        foreach (var element in document.RootElement.EnumerateArray())
        {
            if (element.GetProperty("id").GetString() == id)
            {
                return element.Clone();
            }
        }

        throw new InvalidOperationException($"找不到夹具 {id}");
    }
}
