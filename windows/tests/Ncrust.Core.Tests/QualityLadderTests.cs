using System.Text.Json;
using Ncrust.Core.Playback;
using Xunit;

namespace Ncrust.Core.Tests;

/// <summary>spec/fixtures/quality 的逐条验证。</summary>
public class QualityLadderTests
{
    public static IEnumerable<object[]> Cases() =>
        Spec.LoadJson("fixtures", "quality", "quality.json")
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

        switch (fixture.GetProperty("kind").GetString())
        {
            case "ladder":
                var actual = QualityLadder.FallbackLevels(
                    input.GetProperty("level").GetString()!,
                    input.GetProperty("supportsFlac").GetBoolean());
                Assert.Equal(ExpectedLevels(expect), actual);
                break;
            case "retry":
                var next = expect.GetProperty("nextLevel");
                Assert.Equal(
                    next.ValueKind == JsonValueKind.Null ? null : next.GetString(),
                    QualityLadder.NextRetryLevel(input.GetProperty("level").GetString()!));
                break;
            case "preference":
                Assert.Equal(
                    expect.GetProperty("level").GetString(),
                    QualityLadder.LevelForPreferenceIndex(input.GetProperty("index").GetInt32()));
                break;
            default:
                throw new InvalidOperationException($"夹具 {id} 的 kind 未知");
        }
    }

    [Fact]
    public void IsFlacTier_CoversLosslessHiResJyEffect()
    {
        Assert.True(QualityLadder.IsFlacTier("lossless"));
        Assert.True(QualityLadder.IsFlacTier("hires"));
        Assert.True(QualityLadder.IsFlacTier("jyeffect"));
        Assert.False(QualityLadder.IsFlacTier("dolby"));
        Assert.False(QualityLadder.IsFlacTier("standard"));
    }

    private static string[] ExpectedLevels(JsonElement expect) =>
        expect.GetProperty("levels").EnumerateArray().Select(e => e.GetString()!).ToArray();

    private static JsonElement Load(string id)
    {
        using var document = Spec.LoadJson("fixtures", "quality", "quality.json");
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
