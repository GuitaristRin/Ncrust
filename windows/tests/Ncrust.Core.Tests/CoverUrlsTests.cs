using Ncrust.Core.Api;
using Xunit;

namespace Ncrust.Core.Tests;

public class CoverUrlsTests
{
    [Theory]
    [InlineData(null, null)]
    [InlineData("", null)] // 空串交给图片控件会抛「cannot be converted to type ImageSource」
    [InlineData("   ", null)]
    [InlineData("https://p1.music.126.net/a.jpg", "https://p1.music.126.net/a.jpg?param=640y640")]
    [InlineData("https://p1.music.126.net/a.jpg?x=1", "https://p1.music.126.net/a.jpg?x=1&param=640y640")]
    [InlineData("https://p1.music.126.net/a.jpg?param=300y300", "https://p1.music.126.net/a.jpg?param=300y300")] // 已带尺寸不改
    [InlineData("https://p1.music.126.net/a.jpg?x=1&param=300y300", "https://p1.music.126.net/a.jpg?x=1&param=300y300")]
    [InlineData("ms-appx:///Assets/x.png", "ms-appx:///Assets/x.png")] // 非 http 原样
    public void Small_MatchesAndroid(string? input, string? expected)
    {
        Assert.Equal(expected, CoverUrls.Small(input));
    }

    [Fact]
    public void Large_Uses1080()
    {
        Assert.Equal("https://p1.music.126.net/a.jpg?param=1080y1080", CoverUrls.Large("https://p1.music.126.net/a.jpg"));
    }
}
