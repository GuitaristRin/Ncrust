using Ncrust.Core.Json;
using Xunit;

namespace Ncrust.Core.Tests;

public class JsonValueTests
{
    [Fact]
    public void ParsesObjectArrayAndNestedAccess()
    {
        var value = JsonValue.Parse(
            "{\"code\":200,\"playlist\":{\"name\":\"热歌榜\",\"trackIds\":[{\"id\":1},{\"id\":2}]}}");

        Assert.True(value.IsObject);
        Assert.Equal(200, value.GetInt("code"));

        var playlist = value.GetObject("playlist")!;
        Assert.Equal("热歌榜", playlist.GetString("name"));
        var ids = playlist.GetArray("trackIds")!;
        Assert.Equal(2, ids.Count);
        Assert.Equal(1, ids[0]!.GetLong("id"));
        Assert.Equal(2, ids[1]!.GetLong("id"));
    }

    [Fact]
    public void ParsesScalars()
    {
        Assert.Equal("a\"b\\c/d", JsonValue.Parse("\"a\\\"b\\\\c\\/d\"").StringValue);
        Assert.Equal("line\nbreak\ttab", JsonValue.Parse("\"line\\nbreak\\ttab\"").StringValue);
        Assert.Equal("中文", JsonValue.Parse("\"\\u4e2d\\u6587\"").StringValue);
        Assert.Equal("😀", JsonValue.Parse("\"\\uD83D\\uDE00\"").StringValue);
        Assert.True(JsonValue.Parse("true").BoolValue);
        Assert.False(JsonValue.Parse("false").BoolValue);
        Assert.True(JsonValue.Parse("null").IsNull);
    }

    [Fact]
    public void ParsesNumbers()
    {
        Assert.Equal(200, JsonValue.Parse("200").LongValue);
        Assert.Equal(-7, JsonValue.Parse("-7").LongValue);
        Assert.Equal(109951170048519512L, JsonValue.Parse("109951170048519512").LongValue);
        Assert.Equal(1.5, JsonValue.Parse("1.5").DoubleValue, 6);
        Assert.Equal(250000.0, JsonValue.Parse("2.5e5").DoubleValue, 3);
    }

    [Fact]
    public void MissingKeysFallBack()
    {
        var value = JsonValue.Parse("{\"a\":1}");
        Assert.Null(value["nope"]);
        Assert.False(value.Has("nope"));
        Assert.Equal("fb", value.GetString("nope", "fb"));
        Assert.Equal(9, value.GetInt("nope", 9));
        Assert.False(value.GetBool("nope"));
        Assert.Null(value.GetArray("nope"));
        Assert.Null(value.GetObject("nope"));
    }

    [Fact]
    public void JsonNullBehavesAsMissing()
    {
        var value = JsonValue.Parse("{\"a\":null}");
        Assert.True(value.Has("a"));
        Assert.True(value["a"]!.IsNull);
        Assert.Equal("fb", value.GetString("a", "fb"));
        Assert.Equal(3, value.GetInt("a", 3));
    }

    [Fact]
    public void DuplicateKeyKeepsLastValue()
    {
        var value = JsonValue.Parse("{\"a\":1,\"a\":2}");
        Assert.Equal(2, value.GetInt("a"));
        Assert.Equal(1, value.Count);
    }

    [Fact]
    public void ToleratesWhitespace()
    {
        var value = JsonValue.Parse(" \n\t{ \"a\" : [ 1 , 2 ] } \r\n");
        Assert.Equal(2, value.GetArray("a")!.Count);
    }

    [Fact]
    public void EmptyContainers()
    {
        Assert.Equal(0, JsonValue.Parse("{}").Count);
        Assert.Equal(0, JsonValue.Parse("[]").Count);
    }

    [Theory]
    [InlineData("{")]
    [InlineData("[1,")]
    [InlineData("\"abc")]
    [InlineData("{\"a\" 1}")]
    [InlineData("tru")]
    [InlineData("01x")]
    [InlineData("1.")]
    [InlineData("{} extra")]
    [InlineData("\"\\q\"")]
    public void MalformedThrows(string text)
    {
        Assert.Throws<JsonParseException>(() => JsonValue.Parse(text));
    }
}
