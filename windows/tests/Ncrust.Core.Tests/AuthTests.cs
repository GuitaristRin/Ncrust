using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Auth;
using Ncrust.Core.Net;
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
}

public class QrLoginClientTests
{
    [Fact]
    public async Task RequestKey_ParsesUnikeyAndBuildsChainId()
    {
        var handler = new FakeHandler(_ => Json("{\"code\":200,\"unikey\":\"KEY123\"}"));
        using var http = new NcmHttp(handler);
        var client = new QrLoginClient(http);

        var key = await client.RequestKeyAsync();

        Assert.NotNull(key);
        Assert.Equal("KEY123", key!.Unikey);
        Assert.Equal(52, key.SDeviceId.Length);
        Assert.Equal("https://music.163.com/weapi/login/qrcode/unikey", handler.LastUri);
        Assert.Contains("codekey=KEY123", key.QrUrl);
        Assert.Contains("chainId=v1_" + key.SDeviceId + "_web_login_", key.QrUrl);
        Assert.Null(key.QrImage);
    }

    [Fact]
    public async Task RequestKey_KeepsQrImageWhenPresent()
    {
        var handler = new FakeHandler(_ => Json("{\"code\":200,\"unikey\":\"K\",\"qrimg\":\"data:image/png;base64,AAA\"}"));
        using var http = new NcmHttp(handler);

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
        using var http = new NcmHttp(handler);

        Assert.Null(await new QrLoginClient(http).RequestKeyAsync());
    }

    [Fact]
    public async Task Poll_ScannedCodeHasNoCookie()
    {
        var handler = new FakeHandler(_ => Json("{\"code\":802}"));
        using var http = new NcmHttp(handler);

        var result = await new QrLoginClient(http).PollAsync("KEY", "DEVICE");

        Assert.Equal(QrLoginClient.Scanned, result.Code);
        Assert.Null(result.Cookie);
        Assert.Equal("https://music.163.com/weapi/login/qrcode/client/login", handler.LastUri);
        Assert.Contains("encSecKey=", handler.LastBody);
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
        using var http = new NcmHttp(handler);

        var result = await new QrLoginClient(http).PollAsync("KEY", "DEVICE");

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
        using var http = new NcmHttp(handler);

        var result = await new QrLoginClient(http).PollAsync("KEY", "DEVICE");

        Assert.Equal(QrLoginClient.Succeeded, result.Code);
        Assert.Null(result.Cookie);
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

        protected override async Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            LastUri = request.RequestUri?.ToString();
            if (request.Content != null)
            {
                LastBody = await request.Content.ReadAsStringAsync(cancellationToken);
            }

            return _respond(request);
        }
    }
}
