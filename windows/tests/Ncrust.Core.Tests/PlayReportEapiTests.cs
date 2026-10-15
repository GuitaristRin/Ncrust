using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Auth;
using Ncrust.Core.Json;
using Ncrust.Core.Net;
using Ncrust.Core.Playback;
using Ncrust.Core.Platform;
using Xunit;

namespace Ncrust.Core.Tests;

/// <summary>
/// 播放上报（听歌打卡）的**通道形态**：打到哪、什么身份、两次上报的字段。
///
/// 这些正是「入账与否」的全部变量：旧通道（clientlogusf 明文表单）与 eapi/interface、weapi 三条都
/// 回 HTTP 200 / <c>code 200</c> 却会被服务端静默丢弃（NeteaseCloudMusicApiEnhanced/api-enhanced#167）；
/// 只有 clientlog + eapi + <c>os=osx</c> 入账。测试就在假 handler 上逐项核对请求，并用
/// <see cref="EapiTestBody"/> 解开请求体看加密前的 logs 到底是什么。
///
/// 边界：这些断言证明的是「我们发的就是参考实现那一份请求形态」，**不能证明服务端真的入账** ——
/// 那只能拿真实账号在官方 App 的「最近播放 / 听歌排行」里核对。
/// </summary>
public class PlayReportEapiTests
{
    private const string DeviceId = "0123456789abcdef0123456789abcdef";

    /// <summary>登录 Cookie。deviceId 故意与 <see cref="DeviceId"/> 不同：身份头要用 xeapi 那一个。</summary>
    private const string Cookie = "MUSIC_U=MUSIC-U; __csrf=CSRF; deviceId=stale-device";

    private const string MacUa =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

    [Fact]
    public async Task StartplayThenPlayHitClientLogEapiWithOsxIdentity()
    {
        var handler = new Recorder(_ => Json("{\"code\":200}"));
        using var http = new NcmHttp(handler, XeapiWithDeviceId(DeviceId)) { Cookie = Cookie };

        await SendStartplayAsync(http, 123);
        await SendPlayAsync(http, 123, 45_000);

        Assert.Equal(2, handler.Requests.Count);

        foreach (var request in handler.Requests)
        {
            Assert.Equal("https://clientlog.music.163.com/eapi/feedback/weblog", request.Uri);
            Assert.Equal(MacUa, request.UserAgent);

            var body = request.Body!;
            // 签名路径是 /api/…（请求路径是 /eapi/…）：算错这个，服务端不会报错，只是不入账。
            Assert.Equal("/api/feedback/weblog", EapiTestBody.SignedPath(body));

            var payload = JsonValue.Parse(EapiTestBody.DecryptParams(body));
            Assert.False(payload.GetBool("e_r", true));

            var header = payload.GetObject("header")!;
            Assert.Equal("15.5", header.GetString("osver"));
            Assert.Equal(DeviceId, header.GetString("deviceId"));
            Assert.Equal("osx", header.GetString("os"));
            Assert.Equal("3.1.10.5100", header.GetString("appver"));
            Assert.Equal("140", header.GetString("versioncode"));
            Assert.Equal(string.Empty, header.GetString("mobilename"));
            Assert.Equal("1920x1080", header.GetString("resolution"));
            Assert.Equal("CSRF", header.GetString("__csrf"));
            Assert.Equal("netease", header.GetString("channel"));
            Assert.Equal("MUSIC-U", header.GetString("MUSIC_U"));
            Assert.Matches(@"^\d{10}$", header.GetString("buildver")!);
            Assert.Matches(@"^\d+_\d{4}$", header.GetString("requestId")!);

            // 同一个 header 既进加密 body 也作为 Cookie：逐字段一致（顺序不参与断言，去重参与）。
            var cookie = SessionCookie.Parse(request.Cookie);
            Assert.Equal(header.Count, cookie.Count);
            foreach (var member in header.Members)
            {
                Assert.Equal(member.Value.StringValue, cookie.Get(member.Key));
            }
        }

        // 第一次必须是 startplay（进「最近播放」）。
        var startplay = JsonValue.Parse(LogsOf(handler.Requests[0]));
        Assert.Equal("startplay", startplay[0]!.GetString("action"));
        var startJson = startplay[0]!.GetObject("json")!;
        Assert.Equal(
            new[] { "id", "type", "mainsite", "mainsiteWeb", "content" },
            startJson.Members.Select(m => m.Key));
        Assert.Equal(123, startJson.GetLong("id"));
        Assert.Equal("song", startJson.GetString("type"));
        Assert.Equal("1", startJson.GetString("mainsite"));
        Assert.Equal("1", startJson.GetString("mainsiteWeb"));
        // 来源歌单 / 专辑 id 不知道时是空串（content 保持 id= 形态），不能编一个出来。
        Assert.Equal("id=", startJson.GetString("content"));

        // 第二次必须是 play（涨「听歌排行」）。
        var play = JsonValue.Parse(LogsOf(handler.Requests[1]));
        Assert.Equal("play", play[0]!.GetString("action"));
        var playJson = play[0]!.GetObject("json")!;
        Assert.Equal(
            new[]
            {
                "download", "end", "id", "sourceId", "time", "type", "wifi", "source",
                "mainsite", "mainsiteWeb", "content",
            },
            playJson.Members.Select(m => m.Key));
        Assert.Equal(0, playJson.GetLong("download"));
        Assert.Equal("playend", playJson.GetString("end"));
        Assert.Equal(123, playJson.GetLong("id"));
        Assert.Equal(string.Empty, playJson.GetString("sourceId"));
        Assert.Equal(45, playJson.GetLong("time")); // 秒，不是毫秒
        Assert.Equal("song", playJson.GetString("type"));
        Assert.Equal(0, playJson.GetLong("wifi")); // Wi-Fi
        Assert.Equal("list", playJson.GetString("source"));
        Assert.Equal("id=", playJson.GetString("content"));
    }

    [Fact]
    public async Task OsxHeader_KeepsSourceIdOutOfMusicFieldsAndOnlyAddsMusicUWhenPresent()
    {
        // 未登录（Cookie 里没有 MUSIC_U）：身份头照发，但不补 MUSIC_U / MUSIC_A 两个字段
        // —— 参考实现就是这么写的；「未登录不上报」由调用方（PlaybackEngine）拦。
        var handler = new Recorder(_ => Json("{\"code\":200}"));
        using var http = new NcmHttp(handler, XeapiWithDeviceId(DeviceId)) { Cookie = "__csrf=CSRF" };

        await SendStartplayAsync(http, 1, sourceId: "501");

        var header = HeaderOf(handler.Requests[0]);
        Assert.False(header.Has("MUSIC_U"));
        Assert.False(header.Has("MUSIC_A"));
        Assert.Equal("osx", header.GetString("os"));

        var startJson = JsonValue.Parse(LogsOf(handler.Requests[0]))[0]!.GetObject("json")!;
        // startplay 没有 sourceId 字段，来源 id 只体现在 content 里（参考实现如此）。
        Assert.Equal("id=501", startJson.GetString("content"));
        Assert.False(startJson.Has("sourceId"));
    }

    [Fact]
    public async Task OsxHeader_WithoutXeapiStateReusesOneFallbackDeviceId()
    {
        // 没配 XeapiState（如未接 xeapi 的宿主）时兜底随机 id，但进程内必须稳定：
        // 逐请求换 deviceId 正是风控画像里「非真实设备」的特征。
        var handler = new Recorder(_ => Json("{\"code\":200}"));
        using var http = new NcmHttp(handler) { Cookie = Cookie };

        await SendStartplayAsync(http, 1);
        await SendStartplayAsync(http, 2);

        var first = HeaderOf(handler.Requests[0]).GetString("deviceId");
        var second = HeaderOf(handler.Requests[1]).GetString("deviceId");

        Assert.NotNull(first);
        Assert.Equal(32, first!.Length);
        Assert.Equal(first, second);
    }

    private static Task<HttpResponseMessage> SendStartplayAsync(NcmHttp http, long songId, string? sourceId = null) =>
        http.EapiPostOsxAsync(
            PlayReport.WeblogPath,
            PlayReport.WeblogFields(PlayReport.BuildStartplayLogs(songId, sourceId)));

    private static Task<HttpResponseMessage> SendPlayAsync(NcmHttp http, long songId, long playedMs) =>
        http.EapiPostOsxAsync(
            PlayReport.WeblogPath,
            PlayReport.WeblogFields(PlayReport.BuildPlayLogs(songId, playedMs, isWifi: true)));

    /// <summary>请求体里 <c>logs</c> 的值（服务端拿到的就是这段 JSON 文本）。</summary>
    private static string LogsOf(RecordedRequest request) =>
        JsonValue.Parse(EapiTestBody.DecryptParams(request.Body!)).GetString("logs")!;

    private static JsonValue HeaderOf(RecordedRequest request) =>
        JsonValue.Parse(EapiTestBody.DecryptParams(request.Body!)).GetObject("header")!;

    private static XeapiState XeapiWithDeviceId(string deviceId)
    {
        var settings = new FakeSettings();
        settings.SetString(XeapiState.DeviceIdKey, deviceId);
        return new XeapiState(settings);
    }

    private static HttpResponseMessage Json(string body) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private sealed record RecordedRequest(string Uri, string? UserAgent, string? Cookie, string? Body);

    private sealed class Recorder : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _respond;

        public Recorder(Func<HttpRequestMessage, HttpResponseMessage> respond) => _respond = respond;

        public List<RecordedRequest> Requests { get; } = new();

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            Requests.Add(new RecordedRequest(
                request.RequestUri!.ToString(),
                // User-Agent 是结构化头：TryGetValues 会把 UA 拆成 product / comment 两段，
                // 用属性取回原串。
                request.Headers.UserAgent.ToString(),
                request.Headers.TryGetValues("Cookie", out var cookie) ? string.Join("; ", cookie) : null,
                request.Content == null ? null : await request.Content.ReadAsStringAsync(cancellationToken)));
            return _respond(request);
        }
    }
}
