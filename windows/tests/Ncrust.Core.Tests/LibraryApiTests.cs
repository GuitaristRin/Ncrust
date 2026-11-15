using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Net;
using Ncrust.Core.Net.Crypto;
using Xunit;

namespace Ncrust.Core.Tests;

public class LibraryApiTests
{
    [Theory]
    [InlineData("{\"code\":200}", true)]
    [InlineData("{\"code\":502}", true)]
    [InlineData("{\"code\":524,\"message\":\"当前环境异常，已取消喜欢\"}", false)]
    [InlineData("{\"code\":500}", false)]
    public async Task LikeSong_ChecksXeapiBusinessCode(string body, bool expected)
    {
        var handler = new XeapiHandler(businessJson: _ => body);
        var api = new LibraryApi(new NcmHttp(handler, handler.State));

        Assert.Equal(expected, await api.LikeSongAsync(123, like: true));
        Assert.Single(handler.Requests, r => r.Path == "/xeapi/v1/radio/like");
    }

    /// <summary>
    /// 旧 weapi / eapi radio/like 的写入会被服务端事后回滚（spec/api/xeapi.md §6），
    /// 1.0.4 起不再回退：没有 xeapi 状态时直接失败、一个请求都不发，由上层保留待同步。
    /// </summary>
    [Fact]
    public async Task LikeSong_WithoutXeapiState_FailsWithoutTouchingLegacyChannels()
    {
        var seen = new List<string>();
        var api = new LibraryApi(new NcmHttp(new Handler(path =>
        {
            seen.Add(path);
            return Json("{\"code\":200}");
        })));

        Assert.False(await api.LikeSongAsync(123, like: true));
        Assert.Empty(seen);
    }

    /// <summary>
    /// 首选通道必须是 xeapi（老通道的写会被服务端事后回滚），位置、请求头、Cookie 与
    /// 业务字段都要与官方 Android 客户端同形。
    /// </summary>
    [Fact]
    public async Task LikeSong_UsesXeapiFirst_WithEncryptedHeadersAndLikePayload()
    {
        var handler = new XeapiHandler();
        var http = new NcmHttp(handler, handler.State) { Cookie = "MUSIC_U=u-value; __csrf=csrf-value" };
        var api = new LibraryApi(http);

        Assert.True(await api.LikeSongAsync(2754388325, like: true));

        var like = Assert.Single(handler.Requests, r => r.Path == "/xeapi/v1/radio/like");
        Assert.Equal("https://interface3.music.163.com/xeapi/v1/radio/like", like.Url);
        Assert.Equal("ENCRYPTED", like.Header("X-Client-Enc-State"));
        Assert.Equal("true", like.Header("x-aeapi"));
        Assert.Equal("u-value", like.Header("x-music-u"));
        Assert.Equal(handler.DeviceId, like.Header("x-deviceid"));
        Assert.Equal(handler.DeviceId, like.Header("x-sdeviceid"));
        Assert.Equal("test-anti-cheat-token", like.Header("X-antiCheatToken"));
        Assert.Equal("application/x-www-form-urlencoded; charset=utf-8", like.Header("Content-Type"));
        Assert.Contains("MUSIC_U=u-value", like.Header("Cookie"));
        Assert.Contains("os=android", like.Header("Cookie"));
        Assert.Contains("deviceId=" + handler.DeviceId, like.Header("Cookie"));
        Assert.Contains("sDeviceId=" + handler.DeviceId, like.Header("Cookie"));

        // 解开 C（用 S 里恢复出来的动态密钥），确认送上去的业务字段就是官方 9.6.13
        // NeteaseMusicApiImpl.b7 的完整字段集（spec §6）：九个业务字段一个不少，
        // 易盾 token 同时进体的 checkToken 与 X-antiCheatToken 头。
        // 官方用 HashMap 组字段、顺序不参与指纹，但这里的序列化顺序是稳定契约。
        var plaintext = FakeXeapiServer.DecryptBusinessPlaintext(like.Form("C"), like.Form("S"));
        Assert.Equal(
            "{\"content\":\"trackId=2754388325&time=3&alg=itembased&like=true" +
            "&rqRefer=&songRqRefer=&mode=&subMode=&source=userfm" +
            "&checkToken=test-anti-cheat-token\",\"queryString\":\"e_r=true\"}",
            plaintext);
    }

    [Fact]
    public async Task LikeSong_TreatsXeapi502AsSuccess_NotFallingBackToLegacyChannels()
    {
        // 官方 9.6.13：code 200 与 502（歌曲已在期望状态）都算成功，且 502 后不再走老通道。
        // 若把 502 当失败，会掉进 weapi 老通道；老通道对已收藏歌可能触发风控 524
        // 「当前环境异常，已取消喜欢」，把收藏反手撤销。
        var handler = new XeapiHandler(businessJson: _ => "{\"code\":502}");
        var http = new NcmHttp(handler, handler.State);
        var api = new LibraryApi(http);

        Assert.True(await api.LikeSongAsync(123, like: true));

        Assert.Contains(handler.Requests, r => r.Path == "/xeapi/v1/radio/like");
        Assert.DoesNotContain(handler.Requests, r => r.Path == "/weapi/radio/like");
        Assert.DoesNotContain(handler.Requests, r => r.Path == "/eapi/radio/like");
    }

    [Fact]
    public async Task LikeSong_XeapiRejected_DoesNotFallBackToLegacyChannels()
    {
        var handler = new XeapiHandler(businessJson: _ => "{\"code\":524}");
        var api = new LibraryApi(new NcmHttp(handler, handler.State));

        Assert.False(await api.LikeSongAsync(123, like: true));
        Assert.Single(handler.Requests, r => r.Path == "/xeapi/v1/radio/like");
        Assert.DoesNotContain(handler.Requests, r => r.Path == "/weapi/radio/like");
        Assert.DoesNotContain(handler.Requests, r => r.Path == "/eapi/radio/like");
    }

    [Fact]
    public async Task LikeSong_HttpError_IsFailureNotBusinessBody()
    {
        var handler = new XeapiHandler(businessJson: _ => "{\"code\":200}")
        {
            BusinessStatus = HttpStatusCode.BadGateway,
        };
        var api = new LibraryApi(new NcmHttp(handler, handler.State));

        Assert.False(await api.LikeSongAsync(123, like: true));
        Assert.Single(handler.Requests, r => r.Path == "/xeapi/v1/radio/like");
    }

    /// <summary>
    /// 身份自洽（spec/api/xeapi.md §3.3）：UA、x-appver、cookie appver、公钥请求 appVersion 同一版本，
    /// x-osver / cookie osver 与 UA 里的 Android 版本一致；设备 cookie 只补 mobilename / versioncode。
    /// </summary>
    [Fact]
    public async Task LikeSong_IdentityIsSelfConsistent()
    {
        var handler = new XeapiHandler();
        var http = new NcmHttp(handler, handler.State)
        {
            Cookie = "MUSIC_U=u-value; MUSIC_A=a-value; appver=1.0.0; osver=16",
        };
        var api = new LibraryApi(http);

        Assert.True(await api.LikeSongAsync(1, like: true));

        var like = Assert.Single(handler.Requests, r => r.Path == "/xeapi/v1/radio/like");
        var key = Assert.Single(handler.Requests, r => r.Path == "/api/bsr/sk/get");
        Assert.Equal(XeapiIdentity.UserAgent, like.Header("User-Agent"));
        Assert.Equal(XeapiIdentity.UserAgent, key.Header("User-Agent"));
        Assert.StartsWith("NeteaseMusic/" + XeapiIdentity.AppVersion + ".", XeapiIdentity.UserAgent);
        Assert.Contains("Android " + XeapiIdentity.OsVersion + ";", XeapiIdentity.UserAgent);
        Assert.Equal(XeapiIdentity.AppVersion, like.Header("x-appver"));
        Assert.Equal(XeapiIdentity.OsVersion, like.Header("x-osver"));
        Assert.Equal(XeapiIdentity.AppVersion, key.Form("appVersion"));
        Assert.Equal("a-value", like.Header("x-music-a"));
        Assert.False(like.HasHeader("x-music-tu"));

        var cookie = like.Header("Cookie").Split(';')
            .Select(part => part.Trim().Split('=', 2))
            .ToDictionary(pair => pair[0], pair => pair[1]);
        Assert.Equal(XeapiIdentity.AppVersion, cookie["appver"]);
        Assert.Equal(XeapiIdentity.OsVersion, cookie["osver"]);
        Assert.Equal(XeapiIdentity.Model, cookie["mobilename"]);
        Assert.Equal(XeapiIdentity.BuildNo, cookie["versioncode"]);
        Assert.False(cookie.ContainsKey("brand"));
        Assert.False(cookie.ContainsKey("resolution"));
    }

    [Fact]
    public async Task LikeSong_RequestsPublicKeyOnlyOnce_AcrossLikes()
    {
        var handler = new XeapiHandler();
        var api = new LibraryApi(new NcmHttp(handler, handler.State));

        Assert.True(await api.LikeSongAsync(1, like: true));
        Assert.True(await api.LikeSongAsync(2, like: false));

        Assert.Equal(2, handler.Requests.Count(r => r.Path == "/xeapi/v1/radio/like"));
        // 第二次收藏复用缓存里的公钥，不该再打一次公钥接口。
        Assert.Single(handler.Requests, r => r.Path == "/api/bsr/sk/get");
    }

    /// <summary>公钥过期（nextUpdateTime 已过）时刷新，并把旧 version 填进 currentKeyVersion。</summary>
    [Fact]
    public async Task LikeSong_RefreshesExpiredPublicKey_WithOldVersion()
    {
        var settings = new FakeSettings();
        settings.SetString(XeapiState.PublicKeyKey, FakeXeapiServer.PublicKeyBase64);
        settings.SetString(XeapiState.PublicKeyVersionKey, "999");
        settings.SetString(XeapiState.PublicKeySkKey, "old-sk");
        settings.SetString(XeapiState.NextUpdateTimeKey, "1");

        var handler = new XeapiHandler(new XeapiState(settings));
        var api = new LibraryApi(new NcmHttp(handler, handler.State));

        Assert.True(await api.LikeSongAsync(7, like: true));

        var keyRequest = Assert.Single(handler.Requests, r => r.Path == "/api/bsr/sk/get");
        Assert.Contains("currentKeyVersion=999", keyRequest.Body);
        Assert.Contains("deviceId=" + handler.DeviceId, keyRequest.Body);
        Assert.Equal("deviceId=" + handler.DeviceId, keyRequest.Header("Cookie"));
    }

    [Fact]
    public async Task LikeSong_ReusesDeviceIdFromSettings()
    {
        const string deviceId = "0123456789abcdef0123456789abcdef";
        var settings = new FakeSettings();
        settings.SetString(XeapiState.DeviceIdKey, deviceId);

        var handler = new XeapiHandler(new XeapiState(settings));
        var api = new LibraryApi(new NcmHttp(handler, handler.State));

        Assert.True(await api.LikeSongAsync(1, like: true));
        Assert.Equal(deviceId, handler.DeviceId);
        Assert.Contains("deviceId=" + deviceId, Assert.Single(handler.Requests, r => r.Path == "/api/bsr/sk/get").Body);
    }

    /// <summary>
    /// 并发请求同时发现公钥缺失时只发一次公钥请求（<see cref="XeapiState.EnsureKeyStateAsync"/> 里的
    /// 信号量收口），否则每次冷启动的第一个操作都会打一串 <c>bsr/sk/get</c>。
    /// </summary>
    [Fact]
    public async Task LikeSong_ConcurrentRequests_ShareOnePublicKeyRequest()
    {
        var handler = new XeapiHandler();
        var api = new LibraryApi(new NcmHttp(handler, handler.State));

        var results = await Task.WhenAll(Enumerable.Range(1, 5).Select(id => api.LikeSongAsync(id, like: true)));

        Assert.All(results, Assert.True);
        Assert.Single(handler.Requests, r => r.Path == "/api/bsr/sk/get");
        Assert.Equal(5, handler.Requests.Count(r => r.Path == "/xeapi/v1/radio/like"));
    }

    /// <summary>
    /// 业务响应解不开：服务端可能已执行写入，所以**不重放**（只发一次），本次判失败；
    /// 公钥标记失效，下一次请求强制刷新并带上旧 version。
    /// </summary>
    [Fact]
    public async Task LikeSong_UndecryptableResponse_IsNotReplayed_AndNextRequestRefreshesKey()
    {
        var handler = new XeapiHandler { UndecryptableBusinessResponses = 1 };
        var api = new LibraryApi(new NcmHttp(handler, handler.State));

        Assert.False(await api.LikeSongAsync(5, like: true));
        Assert.Single(handler.Requests, r => r.Path == "/xeapi/v1/radio/like");
        Assert.Single(handler.Requests, r => r.Path == "/api/bsr/sk/get");
        Assert.True(handler.State.KeyInvalidated);

        Assert.True(await api.LikeSongAsync(6, like: true));

        Assert.Equal(2, handler.Requests.Count(r => r.Path == "/xeapi/v1/radio/like"));
        var keyRequests = handler.Requests.Where(r => r.Path == "/api/bsr/sk/get").ToList();
        Assert.Equal(2, keyRequests.Count);
        Assert.DoesNotContain("currentKeyVersion=1000000000000", keyRequests[0].Body);
        Assert.Contains("currentKeyVersion=1000000000000", keyRequests[1].Body);
        Assert.False(handler.State.KeyInvalidated);
    }

    /// <summary>
    /// 易盾 token 取不到时**不提交**写入（Android 19bf30c 的客户端保护，Windows 1.0.4 同步）：
    /// 缺必需字段的写入不发出去，由上层保留待同步。
    /// </summary>
    [Fact]
    public async Task LikeSong_MissingAntiCheatToken_DoesNotSubmit()
    {
        var handler = new XeapiHandler(antiCheatToken: null);
        var api = new LibraryApi(new NcmHttp(handler, handler.State));

        Assert.False(await api.LikeSongAsync(1, like: true));

        Assert.DoesNotContain(handler.Requests, r => r.Path == "/xeapi/v1/radio/like");
    }

    /// <summary>服务端在风控 / 参数错时直接回明文 JSON：照样读出业务码，不当成「解不开」。</summary>
    [Fact]
    public async Task LikeSong_PlainJsonResponse_IsReadAsBusinessBody()
    {
        var handler = new XeapiHandler { PlainBusinessJson = "{\"code\":524}" };
        var api = new LibraryApi(new NcmHttp(handler, handler.State));

        Assert.False(await api.LikeSongAsync(1, like: true));
        Assert.False(handler.State.KeyInvalidated);
    }

    /// <summary>响应头下发会话后，下一次请求要按会话模式走（<c>R = version|ssid</c>，动态密钥换成 sskey）。</summary>
    [Fact]
    public async Task LikeSong_UsesSessionHandedOutByResponseHeaders()
    {
        const string sessionId = "01c3a3532b0d4e6f";
        const string sessionKey = "0123456789abcdef0123456789abcdef";
        var handler = new XeapiHandler { SessionId = sessionId, SessionKey = sessionKey };
        var api = new LibraryApi(new NcmHttp(handler, handler.State));

        Assert.True(await api.LikeSongAsync(1, like: true));
        Assert.True(await api.LikeSongAsync(2, like: true));

        var second = handler.Requests.Last(r => r.Path == "/xeapi/v1/radio/like");
        Assert.Equal(
            Convert.ToBase64String(FakeXeapiServer.AesEcbEncrypt(
                FakeXeapiServer.StaticKey,
                Encoding.UTF8.GetBytes("1000000000000|" + sessionId))),
            second.Form("R"));

        // 会话模式下动态密钥是 sskey 的字节（32 → AES-256），业务字段照样能解开。
        using var plaintext = JsonDocument.Parse(
            FakeXeapiServer.DecryptBusinessPlaintext(second.Form("C"), second.Form("S")));
        var envelope = plaintext.RootElement;
        Assert.Equal(new[] { "content", "queryString" }, envelope.EnumerateObject().Select(p => p.Name).OrderBy(n => n));
        Assert.Equal("e_r=true", envelope.GetProperty("queryString").GetString());

        // 官方业务 map 的顺序不参与指纹（spec/api/xeapi.md §6）；字段集和值必须完整，包含空值。
        var fields = envelope.GetProperty("content").GetString()!.Split('&')
            .Select(pair => pair.Split('=', 2))
            .ToDictionary(pair => WebUtility.UrlDecode(pair[0]), pair => WebUtility.UrlDecode(pair[1]));
        var expected = new Dictionary<string, string>
        {
            ["trackId"] = "2",
            ["time"] = "3",
            ["alg"] = "itembased",
            ["like"] = "true",
            ["rqRefer"] = string.Empty,
            ["songRqRefer"] = string.Empty,
            ["mode"] = string.Empty,
            ["subMode"] = string.Empty,
            ["source"] = "userfm",
            ["checkToken"] = "test-anti-cheat-token",
        };
        Assert.Equal(expected.OrderBy(p => p.Key), fields.OrderBy(p => p.Key));
    }

    [Fact]
    public async Task SubscribeAlbum_UsesSubAndUnsubPaths()
    {
        var seen = new List<string>();
        var handler = new Handler(path =>
        {
            seen.Add(path);
            return Json("{\"code\":200}");
        });
        var api = new LibraryApi(new NcmHttp(handler));

        Assert.True(await api.SubscribeAlbumAsync(9, subscribe: true));
        Assert.True(await api.SubscribeAlbumAsync(9, subscribe: false));
        Assert.Equal(new[] { "/eapi/album/sub", "/eapi/album/unsub" }, seen);
    }

    [Fact]
    public async Task PlaylistTrackIds_ReturnsOrderedIds()
    {
        var api = new PlaylistApi(Router("/eapi/v6/playlist/detail",
            "{\"code\":200,\"playlist\":{\"trackIds\":[{\"id\":1},{\"id\":2},{\"id\":3}]}}"));

        var ids = await api.GetPlaylistTrackIdsAsync(9);

        Assert.Equal(new long[] { 1, 2, 3 }, ids);
    }

    [Fact]
    public async Task PlaylistTrackIds_Non200_Throws()
    {
        var api = new PlaylistApi(Router("/eapi/v6/playlist/detail", "{\"code\":404}"));

        await Assert.ThrowsAsync<NcmApiException>(() => api.GetPlaylistTrackIdsAsync(9));
    }

    [Theory]
    [InlineData("{\"code\":200}")]
    [InlineData("{\"code\":200,\"playlist\":{}}")]
    public async Task PlaylistTrackIds_MissingStructure_Throws(string body)
    {
        var api = new PlaylistApi(Router("/eapi/v6/playlist/detail", body));

        await Assert.ThrowsAsync<NcmApiException>(() => api.GetPlaylistTrackIdsAsync(9));
    }

    [Fact]
    public async Task SubscribedAlbums_ParsesArtistAndCount()
    {
        var api = new AlbumApi(Router("/eapi/album/sublist",
            "{\"code\":200,\"data\":[{\"id\":1,\"name\":\"a\",\"picUrl\":\"p\",\"artists\":[{\"name\":\"ar\"}],\"size\":12}]}"));

        var albums = await api.GetSubscribedAlbumsAsync();

        Assert.Single(albums);
        Assert.Equal(1, albums[0].AlbumId);
        Assert.Equal("ar", albums[0].Artist);
        Assert.Equal(12, albums[0].SongCount);
    }

    /// <summary>收藏专辑翻到短页为止（同 Android 9acf753）：超过一页的账号不再丢最早的专辑。</summary>
    [Fact]
    public async Task AllSubscribedAlbums_PagesUntilShortPage()
    {
        var calls = 0;
        var api = new AlbumApi(new NcmHttp(new Handler(path =>
        {
            if (path != "/eapi/album/sublist")
            {
                return Json("{}");
            }

            calls++;
            return Json(calls switch
            {
                1 => "{\"code\":200,\"data\":[{\"id\":1},{\"id\":2}]}",
                2 => "{\"code\":200,\"data\":[{\"id\":3},{\"id\":4}]}",
                _ => "{\"code\":200,\"data\":[{\"id\":5}]}",
            });
        })));

        var albums = await api.GetAllSubscribedAlbumsAsync(pageSize: 2);

        Assert.Equal(new long[] { 1, 2, 3, 4, 5 }, albums.Select(a => a.AlbumId));
        Assert.Equal(3, calls);
    }

    private static NcmHttp Router(string path, string body) =>
        new(new Handler(p => p == path ? Json(body) : Json("{}")));

    private static HttpResponseMessage Json(string body) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private static HttpResponseMessage Binary(byte[] bytes) => new(HttpStatusCode.OK)
    {
        Content = new ByteArrayContent(bytes),
    };

    private sealed class Handler : HttpMessageHandler
    {
        private readonly System.Func<string, HttpResponseMessage> _respond;

        public Handler(System.Func<string, HttpResponseMessage> respond) => _respond = respond;

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) =>
            Task.FromResult(_respond(request.RequestUri!.AbsolutePath));
    }

    /// <summary>请求发完就被 dispose，所以记录的是拍下来的快照。</summary>
    internal sealed class RecordedRequest
    {
        public RecordedRequest(HttpRequestMessage request, string body)
        {
            Method = request.Method.Method;
            Url = request.RequestUri!.ToString();
            Path = request.RequestUri.AbsolutePath;
            Body = body;
            foreach (var header in request.Headers)
            {
                Headers[header.Key] = string.Join(", ", header.Value);
            }

            if (request.Content != null)
            {
                foreach (var header in request.Content.Headers)
                {
                    Headers[header.Key] = string.Join(", ", header.Value);
                }
            }
        }

        public string Method { get; }

        public string Url { get; }

        public string Path { get; }

        public string Body { get; }

        public Dictionary<string, string> Headers { get; } = new(StringComparer.OrdinalIgnoreCase);

        public string Header(string name) => Headers.TryGetValue(name, out var value) ? value : string.Empty;

        public bool HasHeader(string name) => Headers.ContainsKey(name);

        public string Form(string name) => FormValue(Body, name);

        /// <summary>表单体的某个字段（值按 WHATWG 规则反解，base64 里的 <c>+</c>/<c>=</c> 都在体里被转义过）。</summary>
        internal static string FormValue(string body, string name)
        {
            foreach (var pair in body.Split('&'))
            {
                var eq = pair.IndexOf('=');
                if (eq > 0 && string.Equals(pair.Substring(0, eq), name, StringComparison.Ordinal))
                {
                    return System.Net.WebUtility.UrlDecode(pair.Substring(eq + 1));
                }
            }

            return string.Empty;
        }
    }

    /// <summary>
    /// 假 xeapi 服务端：自持一对 X25519 密钥，应答公钥接口（加密 + 签名，形同真实响应）、
    /// 易盾 token、业务接口（AES-128-ECB 加密的响应），并记录每个请求。
    /// 自持私钥意味着它<em>能解开</em>客户端请求里加密的业务字段，测试因此可以断言业务明文。
    /// </summary>
    internal sealed class XeapiHandler : HttpMessageHandler
    {
        private readonly Func<string, string> _businessJson;
        private readonly string? _antiCheatToken;

        public XeapiHandler(
            XeapiState? state = null,
            Func<string, string>? businessJson = null,
            string? antiCheatToken = "test-anti-cheat-token")
        {
            State = state ?? new XeapiState(new FakeSettings());
            _businessJson = businessJson ?? (_ => "{\"code\":200}");
            _antiCheatToken = antiCheatToken;
        }

        /// <summary>响应头里带上会话，模拟真实服务端下发 <c>x-encr-ssid</c> / <c>x-encr-sskey</c>。</summary>
        public string? SessionId { get; set; }

        public string? SessionKey { get; set; }

        /// <summary>接下来 N 次业务请求返回不可解密的响应体（模拟公钥 / 会话失效）。</summary>
        public int UndecryptableBusinessResponses { get; set; }

        /// <summary>业务请求的 HTTP 状态（模拟网关错误）。</summary>
        public HttpStatusCode BusinessStatus { get; set; } = HttpStatusCode.OK;

        /// <summary>非空时业务请求直接回这段未加密 JSON（模拟服务端风控时的明文响应）。</summary>
        public string? PlainBusinessJson { get; set; }

        public XeapiState State { get; }

        public string DeviceId => State.DeviceId;

        public List<RecordedRequest> Requests { get; } = new();

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            var body = request.Content == null
                ? string.Empty
                : await request.Content.ReadAsStringAsync(cancellationToken);
            var recorded = new RecordedRequest(request, body);
            lock (Requests)
            {
                Requests.Add(recorded);
            }

            switch (recorded.Path)
            {
                case "/api/bsr/sk/get":
                    return Json(BuildPublicKeyResponse(body));
                case "/v3/b": // 易盾
                    return _antiCheatToken == null
                        ? new HttpResponseMessage(HttpStatusCode.ServiceUnavailable)
                        {
                            Content = new StringContent("<html>blocked</html>"),
                        }
                        : new HttpResponseMessage(HttpStatusCode.OK)
                        {
                            Content = new StringContent("null([200,1,\"" + _antiCheatToken + "\"])"),
                        };
                case "/weapi/radio/like":
                    return Json("{\"code\":200}");
                default:
                    if (BusinessStatus != HttpStatusCode.OK)
                    {
                        return new HttpResponseMessage(BusinessStatus) { Content = new StringContent("<html>bad gateway</html>") };
                    }

                    if (PlainBusinessJson != null)
                    {
                        return Json(PlainBusinessJson);
                    }

                    if (UndecryptableBusinessResponses > 0)
                    {
                        UndecryptableBusinessResponses--;
                        return Binary(Encoding.UTF8.GetBytes("<html>not an encrypted response</html>"));
                    }

                    var response = Binary(FakeXeapiServer.EncryptResponse(_businessJson(recorded.Path)));
                    if (!string.IsNullOrEmpty(SessionId) && !string.IsNullOrEmpty(SessionKey))
                    {
                        response.Headers.TryAddWithoutValidation("x-encr-ssid", SessionId);
                        response.Headers.TryAddWithoutValidation("x-encr-sskey", SessionKey);
                    }

                    return response;
            }
        }

        private static string BuildPublicKeyResponse(string requestBody)
        {
            var nonce = RecordedRequest.FormValue(requestBody, "nonce");
            var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture);
            var nextUpdateTime = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds() + 3_600_000;
            var keyJson = "{\"publicKey\":\"" + FakeXeapiServer.PublicKeyBase64 +
                          "\",\"version\":\"1000000000000\",\"sk\":\"8PZfbIFA1779944463972\"," +
                          "\"nextUpdateTime\":" + nextUpdateTime.ToString(CultureInfo.InvariantCulture) + "}";

            return "{\"code\":200,\"data\":{\"encryptedData\":\"" +
                   FakeXeapiServer.EncryptPublicKeyPayload(keyJson) + "\",\"signature\":\"" +
                   XeapiCrypto.Sign(timestamp, nonce) + "\",\"timestamp\":" + timestamp + "}}";
        }
    }

    /// <summary>测试侧的「服务端算法」：只依赖 net9 的加密库与 <see cref="XeapiCrypto"/> 的公开函数。</summary>
    internal static class FakeXeapiServer
    {
        /// <summary>xeapi 的静态 key（协议 §1，AES-256-ECB）。</summary>
        public static readonly byte[] StaticKey = Convert.FromHexString(
            "ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84");

        private static readonly byte[] EapiKey = Encoding.UTF8.GetBytes("e82ckenh8dichen8");
        private static readonly byte[] ServerPrivate = RandomNumberGenerator.GetBytes(32);

        public static string PublicKeyBase64 { get; } =
            Convert.ToBase64String(XeapiCrypto.X25519PublicFromPrivate(ServerPrivate));

        /// <summary>响应体：AES-128-ECB（eapi key）。</summary>
        public static byte[] EncryptResponse(string json) =>
            AesEcbEncrypt(EapiKey, Encoding.UTF8.GetBytes(json));

        /// <summary>公钥接口的 <c>data.encryptedData</c>：AES-256-ECB（静态 key）。</summary>
        public static string EncryptPublicKeyPayload(string json) =>
            Convert.ToBase64String(AesEcbEncrypt(StaticKey, Encoding.UTF8.GetBytes(json)));

        /// <summary>
        /// 完整的「服务端解密」：从 S 里恢复动态密钥（S = ephPub ‖ iv ‖ GCM 密文 ‖ tag），
        /// 再按协议 §3.2 逆向 mid 变换解开 C，得到明文信封 JSON。
        /// </summary>
        public static string DecryptBusinessPlaintext(string c, string s)
        {
            var sBytes = Convert.FromBase64String(s);
            var ephemeralPublic = sBytes[..32];
            var iv = sBytes[32..44];
            var tag = sBytes[^16..];
            var ciphertext = sBytes[44..^16];

            var shared = XeapiCrypto.X25519(ServerPrivate, ephemeralPublic);
            var aesKey = XeapiCrypto.DeriveAesKey(shared, ephemeralPublic);
            var sessionPlaintext = new byte[ciphertext.Length];
            using (var gcm = new AesGcm(aesKey, ManagedAesGcm.TagSize))
            {
                gcm.Decrypt(iv, ciphertext, tag, sessionPlaintext);
            }

            var dynamicKey = Convert.FromBase64String(
                Encoding.UTF8.GetString(sessionPlaintext).Split('|')[0]);

            var mid = AesEcbDecrypt(dynamicKey, DecodeBase64Url(c));
            var random = mid[..16];
            var rest = mid[16..];
            var rot = (random[0] & 0x0f) % rest.Length;

            var xored = new byte[rest.Length];
            Array.Copy(rest, rest.Length - rot, xored, 0, rot);
            Array.Copy(rest, 0, xored, rot, rest.Length - rot);
            for (var i = 0; i < xored.Length; i++)
            {
                xored[i] ^= random[i & 0x0f];
            }

            return Encoding.UTF8.GetString(AesEcbDecrypt(StaticKey, xored));
        }

        private static byte[] DecodeBase64Url(string value)
        {
            var padded = value.Replace('-', '+').Replace('_', '/');
            padded += new string('=', (4 - (padded.Length % 4)) % 4);
            return Convert.FromBase64String(padded);
        }

        public static byte[] AesEcbEncrypt(byte[] key, byte[] data)
        {
            using var aes = Aes.Create();
            aes.Key = key;
            aes.Mode = CipherMode.ECB;
            aes.Padding = PaddingMode.PKCS7;
            using var encryptor = aes.CreateEncryptor();
            return encryptor.TransformFinalBlock(data, 0, data.Length);
        }

        private static byte[] AesEcbDecrypt(byte[] key, byte[] data)
        {
            using var aes = Aes.Create();
            aes.Key = key;
            aes.Mode = CipherMode.ECB;
            aes.Padding = PaddingMode.PKCS7;
            using var decryptor = aes.CreateDecryptor();
            return decryptor.TransformFinalBlock(data, 0, data.Length);
        }
    }
}
