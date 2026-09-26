using Ncrust.Core.Lyrics;
using Xunit;

namespace Ncrust.Core.Tests;

/// <summary>spec/fixtures/lrc 的逐条验证。</summary>
public class LrcParserTests
{
    public static IEnumerable<object[]> Cases() =>
        Spec.LoadJson("fixtures", "lrc", "lrc.json")
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

        var lrc = LrcParser.Parse(input.GetProperty("lrc").GetString());
        var lines = expect.GetProperty("lines").EnumerateArray().ToList();

        if (fixture.GetProperty("kind").GetString() == "parse")
        {
            Assert.Equal(lines.Count, lrc.Count);
            for (var i = 0; i < lines.Count; i++)
            {
                Assert.Equal(lines[i].GetProperty("timeMs").GetInt64(), lrc[i].TimeMs);
                Assert.Equal(lines[i].GetProperty("text").GetString(), lrc[i].Text);
            }

            return;
        }

        var tlyric = input.TryGetProperty("tlyric", out var t) && t.ValueKind != System.Text.Json.JsonValueKind.Null
            ? LrcParser.Parse(t.GetString())
            : System.Array.Empty<LrcLine>();

        var merged = LyricMerger.Merge(lrc, tlyric);
        Assert.Equal(lines.Count, merged.Count);
        for (var i = 0; i < lines.Count; i++)
        {
            Assert.Equal(lines[i].GetProperty("timeMs").GetInt64(), merged[i].TimeMs);
            Assert.Equal(lines[i].GetProperty("text").GetString(), merged[i].Text);
            Assert.Equal(lines[i].GetProperty("translation").GetString(), merged[i].Translation);
        }
    }

    [Fact]
    public void Parse_NullOrEmpty_IsEmpty()
    {
        Assert.Empty(LrcParser.Parse(null));
        Assert.Empty(LrcParser.Parse(string.Empty));
    }

    private static System.Text.Json.JsonElement Load(string id)
    {
        using var document = Spec.LoadJson("fixtures", "lrc", "lrc.json");
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
