using System;
using System.Net;
using System.Net.Http;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Auth;
using Ncrust.Core.Json;
using Ncrust.Core.Net;
using Ncrust.Core.Net.Crypto;
using Xunit;

namespace Ncrust.Core.Tests;

public class SessionCookieTests
{
    [Fact]
    public void Parse_SplitsTrimsAndSkipsInvalid()
    {
        var cookie = SessionCookie.Parse(" MUSIC_U=abc ; __csrf=xyz; novalue;=bad; ");

        Assert.Equal(2, cookie.Count);
        Assert.Equal("abc", cookie.MusicU);
        Assert.Equal("xyz", cookie.Csrf);
        Assert.Null(cookie.Get("novalue"));
    }

    [Fact]
    public void Parse_NullOrEmpty_IsEmpty()
    {
        Assert.Equal(0, SessionCookie.Parse(null).Count);
        Assert.Equal(0, SessionCookie.Parse("").Count);
    }

    [Fact]
    public void Set_OverridesSameNameKeepingPosition()
    {
        var cookie = SessionCookie.Parse("a=1; b=2");
        cookie.Set("a", "9");

        Assert.Equal(2, cookie.Count);
        Assert.Equal("9", cookie.Get("a"));
        Assert.Equal("a=9; b=2", cookie.ToString());
    }

    [Fact]
    public void Merge_OverridesFromOther()
    {
        var cookie = SessionCookie.Parse("a=1; b=2");
        cookie.Merge(SessionCookie.Parse("b=20; c=3"));

        Assert.Equal(3, cookie.Count);
        Assert.Equal("a=1; b=20; c=3", cookie.ToString());
    }

    [Fact]
    public void ValuesMayContainEquals()
    {
        var cookie = SessionCookie.Parse("MUSIC_U=aa==; __csrf=b");
        Assert.Equal("aa==", cookie.MusicU);
    }

    [Theory]
    [InlineData("MUSIC_U=abc", true)]
    [InlineData("MUSIC_U=aa==", true)]
    [InlineData("MUSIC_U=", false)]
    [InlineData("__csrf=xyz", false)]
    [InlineData("", false)]
    public void HasMusicU_RequiresNonEmptyValue(string cookie, bool expected) =>
        Assert.Equal(expected, SessionCookie.Parse(cookie).HasMusicU);

    [Fact]
    public void Parse_LastWriteWinsEvenWhenEmpty()
    {
        // Parse 保持「同名最后一次写入为准」；Set-Cookie 的删除语义只在 ParseSetCookie 里。
        Assert.Equal(string.Empty, SessionCookie.Parse("MUSIC_U=abc; MUSIC_U=").MusicU);
    }

    [Fact]
    public void ParseSetCookie_KeepsLastNonEmptyValue()
    {
        // 删除写法（空值）与真值可能以任意顺序出现在同一响应里：判定取最后一个非空值。
        Assert.Equal("abc", SessionCookie.ParseSetCookie("MUSIC_U=; MUSIC_U=abc").MusicU);
        Assert.Equal("abc", SessionCookie.ParseSetCookie("MUSIC_U=abc; MUSIC_U=").MusicU);
        Assert.False(SessionCookie.ParseSetCookie("MUSIC_U=").HasMusicU);
        Assert.False(SessionCookie.ParseSetCookie("MUSIC_U=; __csrf=x").HasMusicU);
        Assert.Equal("x", SessionCookie.ParseSetCookie("MUSIC_U=; __csrf=x").Csrf);
    }
}

public class QrLoginClientTests
{
    /// <summary>32 位小写 hex：与 <see cref="XeapiState.DeviceId"/> 的持久形态一致。</summary>
    private const string DeviceId = "0123456789abcdef0123456789abcdef";

    [Fact]
    public async Task RequestKey_HitsEapiPcWithType3AndPcHeader()
    {
        var handler = new FakeHandler(_ => Json("{\"code\":200,\"unikey\":\"KEY123\"}"));
        using var http = new NcmHttp(handler, XeapiWithDeviceId(DeviceId));

        var key = await new QrLoginClient(http).RequestKeyAsync();

        Assert.NotNull(key);
        Assert.Equal("KEY123", key!.Unikey);
        Assert.Equal("https://interfacepc.music.163.com/eapi/login/qrcode/unikey", handler.LastUri);

        // 解密后必须是参考实现的明文形态：type 是数值 3、e_r 是布尔 false、header 是 PC 身份。
        var plaintext = DecryptParams(handler.LastBody!);
        Assert.Contains("\"type\":3", plaintext);
        var json = JsonValue.Parse(plaintext);
        Assert.False(json.GetBool("e_r", true));

        var header = json.GetObject("header");
        Assert.NotNull(header);
        Assert.Equal("pc", header!.GetString("os"));
        Assert.Equal(DeviceId, header.GetString("deviceId"));
        Assert.Equal("Microsoft-Windows-10-Professional-build-19045-64bit", header.GetString("osver"));
        Assert.Equal("3.1.17.204416", header.GetString("appver"));
        Assert.Equal("140", header.GetString("versioncode"));
        Assert.Equal("1920x1080", header.GetString("resolution"));
        Assert.Equal("netease", header.GetString("channel"));
        Assert.Equal(string.Empty, header.GetString("__csrf"));
        Assert.Matches(@"^\d{10}$", header.GetString("buildver")!);
        Assert.Matches(@"^\d+_\d{4}$", header.GetString("requestId")!);

        // 同一个 header 既进加密 body 也作为 Cookie：逐字段一致（顺序不参与断言，去重参与）。
        var cookie = SessionCookie.Parse(handler.LastCookie);
        Assert.Equal(header.Count, cookie.Count);
        foreach (var member in header.Members)
        {
            Assert.Equal(member.Value.StringValue, cookie.Get(member.Key));
        }
    }

    [Fact]
    public async Task RequestKey_QrUrlIsPcFormWithoutChainId()
    {
        // PC 身份不用 web 登录会话的 chainId：带上反而不被认（见 spec/api/endpoints.md）。
        var handler = new FakeHandler(_ => Json("{\"code\":200,\"unikey\":\"KEY123\"}"));
        using var http = new NcmHttp(handler, XeapiWithDeviceId(DeviceId));

        var key = await new QrLoginClient(http).RequestKeyAsync();

        Assert.Equal("https://music.163.com/login?codekey=KEY123", key!.QrUrl);
    }

    [Fact]
    public async Task RequestKey_KeepsQrImageWhenPresent()
    {
        var handler = new FakeHandler(_ => Json("{\"code\":200,\"unikey\":\"K\",\"qrimg\":\"data:image/png;base64,AAA\"}"));
        using var http = new NcmHttp(handler, XeapiWithDeviceId(DeviceId));

        var key = await new QrLoginClient(http).RequestKeyAsync();

        Assert.Equal("data:image/png;base64,AAA", key!.QrImage);
    }

    [Theory]
    [InlineData("{\"code\":801}")]
    [InlineData("{\"code\":200}")]
    [InlineData("not json")]
    public async Task RequestKey_FailsGracefully(string body)
    {
        var handler = new FakeHandler(_ => Json(body));
        using var http = new NcmHttp(handler, XeapiWithDeviceId(DeviceId));

        Assert.Null(await new QrLoginClient(http).RequestKeyAsync());
    }

    [Fact]
    public async Task Poll_HitsEapiPcWithKeyAndType3()
    {
        var handler = new FakeHandler(_ => Json("{\"code\":802}"));
        using var http = new NcmHttp(handler, XeapiWithDeviceId(DeviceId));
        // 扫码时还没登录；即便残留了登录 cookie，扫码请求也不该把它发出去。
        http.Cookie = "MUSIC_U=stale";

        var result = await new QrLoginClient(http).PollAsync("KEY");

        Assert.Equal(QrLoginClient.Scanned, result.Code);
        Assert.Null(result.Cookie);
        Assert.Equal("https://interfacepc.music.163.com/eapi/login/qrcode/client/login", handler.LastUri);
        var plaintext = DecryptParams(handler.LastBody!);
        Assert.Contains("\"key\":\"KEY\"", plaintext);
        Assert.Contains("\"type\":3", plaintext);
        Assert.Equal("pc", JsonValue.Parse(plaintext).GetObject("header")?.GetString("os"));
        Assert.DoesNotContain("MUSIC_U", handler.LastCookie!);
    }

    [Fact]
    public async Task Poll_SuccessExtractsSessionCookie()
    {
        var handler = new FakeHandler(_ =>
        {
            var response = Json("{\"code\":803}");
            response.Headers.TryAddWithoutValidation("Set-Cookie", "MUSIC_U=abc; Path=/; HttpOnly");
            response.Headers.TryAddWithoutValidation("Set-Cookie", "__csrf=xyz; Path=/");
            return response;
        });
        using var http = new NcmHttp(handler, XeapiWithDeviceId(DeviceId));

        var result = await new QrLoginClient(http).PollAsync("KEY");

        Assert.Equal(QrLoginClient.Succeeded, result.Code);
        Assert.NotNull(result.Cookie);
        Assert.Contains("MUSIC_U=abc", result.Cookie);
        Assert.Contains("__csrf=xyz", result.Cookie);
    }

    [Fact]
    public async Task Poll_SuccessWithoutMusicU_ReturnsNoCookie()
    {
        var handler = new FakeHandler(_ =>
        {
            var response = Json("{\"code\":803}");
            response.Headers.TryAddWithoutValidation("Set-Cookie", "NMTID=1; Path=/");
            return response;
        });
        using var http = new NcmHttp(handler, XeapiWithDeviceId(DeviceId));

        var result = await new QrLoginClient(http).PollAsync("KEY");

        Assert.Equal(QrLoginClient.Succeeded, result.Code);
        Assert.Null(result.Cookie);
    }

    [Fact]
    public async Task Poll_SuccessWithEmptyMusicU_ReturnsNoCookie()
    {
        // 服务端把会话置空时给的是 `MUSIC_U=`（删除写法）：空值不是登录成功。
        var handler = new FakeHandler(_ =>
        {
            var response = Json("{\"code\":803}");
            response.Headers.TryAddWithoutValidation("Set-Cookie", "MUSIC_U=; Path=/; Max-Age=0");
            return response;
        });
        using var http = new NcmHttp(handler, XeapiWithDeviceId(DeviceId));

        var result = await new QrLoginClient(http).PollAsync("KEY");

        Assert.Equal(QrLoginClient.Succeeded, result.Code);
        Assert.Null(result.Cookie);
    }

    [Theory]
    [InlineData("MUSIC_U=; Path=/", "MUSIC_U=real; Path=/; HttpOnly")]
    [InlineData("MUSIC_U=real; Path=/; HttpOnly", "MUSIC_U=; Path=/")]
    public async Task Poll_SuccessWithDeleteAndRealMusicU_KeepsLastNonEmptyValue(string first, string second)
    {
        var handler = new FakeHandler(_ =>
        {
            var response = Json("{\"code\":803}");
            response.Headers.TryAddWithoutValidation("Set-Cookie", first);
            response.Headers.TryAddWithoutValidation("Set-Cookie", second);
            return response;
        });
        using var http = new NcmHttp(handler, XeapiWithDeviceId(DeviceId));

        var result = await new QrLoginClient(http).PollAsync("KEY");

        Assert.NotNull(result.Cookie);
        Assert.Contains("MUSIC_U=real", result.Cookie);
    }

    [Fact]
    public async Task Poll_WithoutXeapiState_ReusesOneFallbackDeviceId()
    {
        // 没配 XeapiState（如未接 xeapi 的宿主）时兜底随机 id，但进程内必须稳定：
        // 逐请求换 deviceId 正是风控画像里「非真实设备」的特征。
        var handler = new FakeHandler(_ => Json("{\"code\":801}"));
        using var http = new NcmHttp(handler);
        var client = new QrLoginClient(http);

        await client.PollAsync("KEY");
        var first = SessionCookie.Parse(handler.LastCookie).Get("deviceId");
        await client.PollAsync("KEY");
        var second = SessionCookie.Parse(handler.LastCookie).Get("deviceId");

        Assert.NotNull(first);
        Assert.Equal(32, first!.Length);
        Assert.Equal(first, second);
    }

    private static XeapiState XeapiWithDeviceId(string deviceId)
    {
        var settings = new FakeSettings();
        settings.SetString(XeapiState.DeviceIdKey, deviceId);
        return new XeapiState(settings);
    }

    /// <summary>
    /// 把请求体 <c>params=&lt;hex&gt;</c> 解回加密前的明文，并取出其中的业务 JSON：
    /// eapi 明文是 <c>&lt;path&gt;-36cd479b6b5-&lt;json&gt;-36cd479b6b5-&lt;digest&gt;</c>。
    /// </summary>
    private static string DecryptParams(string body)
    {
        var value = body.Substring(body.IndexOf('=') + 1);
        var bytes = new byte[value.Length / 2];
        for (var i = 0; i < bytes.Length; i++)
        {
            bytes[i] = Convert.ToByte(value.Substring(i * 2, 2), 16);
        }

        using var aes = Aes.Create();
        aes.Key = EapiCrypto.AesKey;
        aes.Mode = CipherMode.ECB;
        aes.Padding = PaddingMode.PKCS7;
        using var decryptor = aes.CreateDecryptor();
        var plaintext = Encoding.UTF8.GetString(decryptor.TransformFinalBlock(bytes, 0, bytes.Length));

        var parts = plaintext.Split(new[] { "-36cd479b6b5-" }, StringSplitOptions.None);
        Assert.Equal(3, parts.Length);
        return parts[1];
    }

    private static HttpResponseMessage Json(string body) => new HttpResponseMessage(HttpStatusCode.OK)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    private sealed class FakeHandler : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _respond;

        public FakeHandler(Func<HttpRequestMessage, HttpResponseMessage> respond) => _respond = respond;

        public string? LastUri { get; private set; }

        public string? LastBody { get; private set; }

        public string? LastCookie { get; private set; }

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            LastUri = request.RequestUri?.ToString();
            LastCookie = request.Headers.TryGetValues("Cookie", out var values)
                ? string.Join("; ", values)
                : null;
            if (request.Content != null)
            {
                LastBody = await request.Content.ReadAsStringAsync(cancellationToken);
            }

            return _respond(request);
        }
    }
}

public class AccountApiSessionValidationTests
{
    private const string Cookie = "MUSIC_U=abc; __csrf=xyz";

    [Fact]
    public async Task ValidateSession_ProfilePresent_IsValid()
    {
        var handler = new Recorder(_ => Json("{\"code\":200,\"profile\":{\"userId\":7,\"nickname\":\"n\"}}"));

        Assert.Equal(SessionValidation.Valid, await AccountApi.ValidateSessionAsync(Cookie, handler));
    }

    [Fact]
    public async Task ValidateSession_AccountOnly_IsValid()
    {
        // 与 GetProfileAsync 的判定一致：account 或 profile 任一非空即算登录。
        var handler = new Recorder(_ => Json("{\"code\":200,\"account\":{\"id\":7,\"userName\":\"n\"}}"));

        Assert.Equal(SessionValidation.Valid, await AccountApi.ValidateSessionAsync(Cookie, handler));
    }

    [Theory]
    [InlineData("{\"code\":301,\"msg\":\"需要登录\"}")]
    [InlineData("{\"code\":200,\"account\":null,\"profile\":null}")]
    public async Task ValidateSession_ExplicitlyAnonymous_IsInvalid(string body)
    {
        var handler = new Recorder(_ => Json(body));

        Assert.Equal(SessionValidation.Invalid, await AccountApi.ValidateSessionAsync(Cookie, handler));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("__csrf=xyz")]
    [InlineData("MUSIC_U=")]
    public async Task ValidateSession_WithoutNonEmptyMusicU_IsInvalidWithoutRequest(string? cookie)
    {
        var handler = new Recorder(_ => throw new InvalidOperationException("不该发请求"));

        Assert.Equal(SessionValidation.Invalid, await AccountApi.ValidateSessionAsync(cookie, handler));
    }

    [Fact]
    public async Task ValidateSession_NetworkFailure_IsUnknown()
    {
        // 断网不能判成「无效」：上层会据此清掉登录态。
        var handler = new Recorder(_ => throw new HttpRequestException("no network"));

        Assert.Equal(SessionValidation.Unknown, await AccountApi.ValidateSessionAsync(Cookie, handler));
    }

    [Fact]
    public async Task ValidateSession_ServerError_IsUnknown()
    {
        var handler = new Recorder(_ => new HttpResponseMessage(HttpStatusCode.BadGateway)
        {
            Content = new StringContent("{}", Encoding.UTF8, "application/json"),
        });

        Assert.Equal(SessionValidation.Unknown, await AccountApi.ValidateSessionAsync(Cookie, handler));
    }

    [Fact]
    public async Task ValidateSession_UnparsableBody_IsUnknown()
    {
        var handler = new Recorder(_ => Json("<html>502</html>"));

        Assert.Equal(SessionValidation.Unknown, await AccountApi.ValidateSessionAsync(Cookie, handler));
    }

    [Fact]
    public async Task ValidateSession_SendsGivenCookieToAccountEndpoint()
    {
        var handler = new Recorder(_ => Json("{\"code\":200,\"profile\":{\"userId\":7}}"));

        await AccountApi.ValidateSessionAsync(Cookie, handler);

        Assert.Equal("https://interface.music.163.com/eapi/w/nuser/account/get", handler.LastUri);
        Assert.Equal(Cookie, handler.LastCookie);
    }

    [Fact]
    public async Task GetProfile_ExplicitlyAnonymous_ReturnsNull()
    {
        var api = new AccountApi(new NcmHttp(new Recorder(_ => Json("{\"code\":301}"))));

        Assert.Null(await api.GetProfileAsync());
    }

    [Fact]
    public async Task GetProfile_ServerError_ThrowsInsteadOfLookingAnonymous()
    {
        // 上层把 null 当成「会话失效」：HTTP 层失败必须抛出去，不能被误判成未登录。
        var api = new AccountApi(new NcmHttp(new Recorder(_ => new HttpResponseMessage(HttpStatusCode.BadGateway))));

        await Assert.ThrowsAsync<NcmApiException>(() => api.GetProfileAsync());
    }

    private static HttpResponseMessage Json(string body) => new HttpResponseMessage(HttpStatusCode.OK)
    {
        Content = new StringContent(body, Encoding.UTF8, "application/json"),
    };

    /// <summary>记录请求的假 handler：断言打的路径与带的 cookie，也用来模拟断网 / 502。</summary>
    private sealed class Recorder : HttpMessageHandler
    {
        private readonly Func<HttpRequestMessage, HttpResponseMessage> _respond;

        public Recorder(Func<HttpRequestMessage, HttpResponseMessage> respond) => _respond = respond;

        public string? LastUri { get; private set; }

        public string? LastCookie { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            LastUri = request.RequestUri?.ToString();
            LastCookie = request.Headers.TryGetValues("Cookie", out var values)
                ? string.Join("; ", values)
                : null;

            return Task.FromResult(_respond(request));
        }
    }
}
