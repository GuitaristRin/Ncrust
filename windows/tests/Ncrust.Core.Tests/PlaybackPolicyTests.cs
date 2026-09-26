using Ncrust.Core.Json;
using Ncrust.Core.Net;
using Ncrust.Core.Playback;
using Ncrust.Core.Platform;
using Xunit;

namespace Ncrust.Core.Tests;

public class PlayReportTests
{
    [Theory]
    [InlineData(0, 100, false)]
    [InlineData(79, 100, false)]
    [InlineData(80, 100, true)]
    [InlineData(100, 100, true)]
    [InlineData(0, 0, false)]
    public void ReachedCompletion(long position, long duration, bool expected) =>
        Assert.Equal(expected, PlayReportPolicy.ReachedCompletion(position, duration));

    [Fact]
    public void TryAcquire_ReportsOncePerSong()
    {
        var policy = new PlayReportPolicy();

        Assert.False(policy.TryAcquire(1, 10, 100, ended: false));
        Assert.True(policy.TryAcquire(1, 80, 100, ended: false));
        Assert.False(policy.TryAcquire(1, 99, 100, ended: false)); // 已报过
        Assert.False(policy.TryAcquire(1, 100, 100, ended: true)); // 自然结束也不重复
        Assert.True(policy.TryAcquire(2, 100, 100, ended: true));  // 换歌后可报
    }

    [Fact]
    public void TryAcquire_EndedIgnoresThreshold()
    {
        var policy = new PlayReportPolicy();
        Assert.True(policy.TryAcquire(7, 5, 100, ended: true));
    }

    [Fact]
    public void BuildLogs_MatchesOfficialShape()
    {
        var logs = PlayReport.BuildLogs(123, 45000, "playend", null, isWifi: true);

        Assert.Equal(
            "[{\"action\":\"play\",\"json\":{\"type\":\"song\",\"wifi\":0,\"download\":0,\"id\":123,\"time\":45000,\"end\":\"playend\",\"mainsite\":\"1\",\"mainsiteWeb\":\"1\"}}]",
            logs);
    }

    [Fact]
    public void BuildLogs_MeteredNetworkFlipsWifiTo1_AndIncludesStrategy()
    {
        var logs = PlayReport.BuildLogs(9, 1000, "interrupt", "itembased", isWifi: false);

        Assert.Contains("\"wifi\":1", logs);
        Assert.Contains("\"alg\":\"itembased\"", logs);
        Assert.Contains("\"end\":\"interrupt\"", logs);
    }

    [Fact]
    public void WeblogUrl_EscapesCsrf()
    {
        Assert.Equal(
            "https://clientlogusf.music.163.com/api/feedback/weblog?csrf_token=a%2Bb",
            PlayReport.WeblogUrl("a+b"));
    }
}

public class SongUrlResolverTests
{
    [Fact]
    public async Task FallsBackDownTheLadder()
    {
        var handler = new Handler(level => level == "lossless"
            ? Json("{\"code\":200,\"data\":[{\"code\":404}]}")
            : Json("{\"code\":200,\"data\":[{\"code\":200,\"url\":\"http://x\",\"level\":\"exhigh\",\"type\":\"mp3\",\"br\":320000}]}"));
        using var http = new NcmHttp(handler);
        var resolver = new SongUrlResolver(http, new FlacProbe(true));

        var result = await resolver.ResolveAsync(1, "lossless");

        Assert.NotNull(result);
        Assert.Equal("http://x", result!.Url);
        Assert.Equal("exhigh", result.ActualLevel);
        Assert.Equal("mp3", result.ContainerType);
        Assert.Equal(320000, result.BitRate);
        Assert.Equal(new[] { "lossless", "exhigh" }, handler.RequestedLevels);
    }

    [Fact]
    public async Task FlacGate_SkipsLosslessWhenUnsupported()
    {
        var handler = new Handler(_ => Json("{\"code\":200,\"data\":[{\"code\":200,\"url\":\"http://s\",\"level\":\"standard\"}]}"));
        using var http = new NcmHttp(handler);
        var resolver = new SongUrlResolver(http, new FlacProbe(false));

        var result = await resolver.ResolveAsync(1, "lossless");

        Assert.Equal("standard", result!.ActualLevel);
        Assert.Equal("exhigh", handler.RequestedLevels[0]);
    }

    [Fact]
    public async Task Dolby_UsesMp4Container()
    {
        var handler = new Handler(_ => Json("{\"code\":200,\"data\":[{\"code\":200,\"url\":\"http://d\",\"level\":\"dolby\",\"type\":\"mp4\"}]}"));
        using var http = new NcmHttp(handler);
        var resolver = new SongUrlResolver(http, new FlacProbe(true));

        var result = await resolver.ResolveAsync(1, "dolby");

        Assert.Equal("mp4", result!.ContainerType);
        Assert.Equal("mp4", handler.RequestedEncodeTypes[0]);
        Assert.Single(handler.RequestedLevels);
    }

    [Fact]
    public async Task AllLevelsFail_ReturnsNull()
    {
        var handler = new Handler(_ => Json("{\"code\":200,\"data\":[{\"code\":404}]}"));
        using var http = new NcmHttp(handler);
        var resolver = new SongUrlResolver(http, new FlacProbe(true));

        Assert.Null(await resolver.ResolveAsync(1, "higher"));
        Assert.Equal(new[] { "higher", "standard" }, handler.RequestedLevels);
    }

    private static HttpResponseMessage Json(string body) => new(System.Net.HttpStatusCode.OK)
    {
        Content = new System.Net.Http.StringContent(body, System.Text.Encoding.UTF8, "application/json"),
    };

    private sealed class FlacProbe : ICodecProbe
    {
        public FlacProbe(bool supportsFlac) => SupportsFlac = supportsFlac;

        public bool SupportsFlac { get; }
    }

    private sealed class Handler : HttpMessageHandler
    {
        private readonly Func<string, HttpResponseMessage> _respond;

        public Handler(Func<string, HttpResponseMessage> respond) => _respond = respond;

        public List<string> RequestedLevels { get; } = new();

        public List<string> RequestedEncodeTypes { get; } = new();

        protected override async Task<HttpResponseMessage> SendAsync(
            System.Net.Http.HttpRequestMessage request,
            System.Threading.CancellationToken cancellationToken)
        {
            var body = request.Content == null ? string.Empty : await request.Content.ReadAsStringAsync(cancellationToken);
            var payload = ParsePayload(body);
            RequestedLevels.Add(payload.GetString("level")!);
            RequestedEncodeTypes.Add(payload.GetString("encodeType")!);
            return _respond(payload.GetString("level")!);
        }

        private static JsonValue ParsePayload(string formBody)
        {
            var hex = formBody.Substring(formBody.IndexOf('=') + 1);
            using var aes = System.Security.Cryptography.Aes.Create();
            aes.Key = System.Text.Encoding.UTF8.GetBytes("e82ckenh8dichen8");
            aes.Mode = System.Security.Cryptography.CipherMode.ECB;
            aes.Padding = System.Security.Cryptography.PaddingMode.PKCS7;
            using var decryptor = aes.CreateDecryptor();
            var bytes = Convert.FromHexString(hex);
            var plain = System.Text.Encoding.UTF8.GetString(decryptor.TransformFinalBlock(bytes, 0, bytes.Length));
            var json = plain.Split(new[] { "-36cd479b6b5-" }, StringSplitOptions.None)[1];
            return JsonValue.Parse(json);
        }
    }
}
