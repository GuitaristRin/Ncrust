using System;
using System.Collections.Generic;
using System.Globalization;
using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Auth;
using Ncrust.Core.Json;
using Ncrust.Core.Net.Crypto;
using Ncrust.Core.Util;

namespace Ncrust.Core.Net
{
    /// <summary>
    /// 网易云请求网关（对应 Android <c>RetrofitClient</c>）。三种协议共存：
    /// REST GET、eapi（AES-128-ECB + MD5 签名）、weapi（双层 AES-CBC + raw RSA）。
    ///
    /// 关键点：<see cref="HttpClientHandler.UseCookies"/> 必须为 <c>false</c>，
    /// 否则平台会自行附加/吞掉 Cookie，登录态与反风控字段都会错。Cookie 由上层显式设置。
    /// </summary>
    public sealed class NcmHttp : IDisposable
    {
        public const string BaseUrl = "https://music.163.com";
        public const string InterfaceUrl = "https://interface3.music.163.com";
        public const string ApiUrl = "https://interface.music.163.com";

        /// <summary>eapi 的 PC 客户端域：扫码登录的 unikey / client/login 只在这个域上认 PC 身份。</summary>
        public const string InterfacePcUrl = "https://interfacepc.music.163.com";

        /// <summary>播放上报（听歌打卡）的日志域（参考实现 <c>util/config.json</c> 的 <c>clDomian</c>）。</summary>
        public const string ClientLogUrl = "https://clientlog.music.163.com";

        private const string Ua =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

        private const string IosUa =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1";

        /// <summary>桌面客户端 UA（网易云音乐 PC 版）：扫码登录的 eapi 接口认这套指纹。</summary>
        private const string PcUa =
            "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.29.205117";

        /// <summary>
        /// os=osx 身份专用的 Mac Chrome UA：参考实现的 eapi 分支里 <c>cookie.os === 'osx'</c>
        /// 时才用它，播放上报非它不可。
        /// </summary>
        private const string OsxUa =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

        /// <summary>
        /// 没有 <see cref="Xeapi"/> 时（如未接 xeapi 的宿主）的兜底设备 id，进程内只生成一次。
        /// 逐请求换 deviceId 正是风控画像里「非真实设备」的特征，见 <see cref="XeapiState"/>。
        /// </summary>
        private static string? _fallbackDeviceId;

        private readonly HttpClient _client;
        private readonly bool _ownsHandler;

        /// <summary>当前登录 Cookie 原串。未登录时为 null。</summary>
        public string? Cookie { get; set; }

        /// <summary>
        /// xeapi 的设备 id / 公钥 / 会话状态。为 null 时 <see cref="XeapiPostAsync"/> 直接失败，
        /// 调用方（如 <c>LibraryApi</c>）会回退到老通道。
        /// </summary>
        public XeapiState? Xeapi { get; set; }

        public NcmHttp(HttpMessageHandler? handler = null, XeapiState? xeapiState = null)
        {
            _ownsHandler = handler == null;
            _client = new HttpClient(handler ?? CreateHandler(), _ownsHandler)
            {
                Timeout = TimeSpan.FromSeconds(30),
            };

            Xeapi = xeapiState;
        }

        private static HttpClientHandler CreateHandler() => new HttpClientHandler
        {
            // 必须关掉：否则 UWP 会自行管理 Cookie 容器，附加/吞掉我们手工设置的 Cookie。
            UseCookies = false,
            AutomaticDecompression = DecompressionMethods.GZip | DecompressionMethods.Deflate,
        };

        /// <summary>eapi POST。<paramref name="useInterface"/> 为 true 时打到 interface3（取歌曲 URL 用）。</summary>
        public async Task<HttpResponseMessage> EapiPostAsync(
            string path,
            IEnumerable<KeyValuePair<string, string>>? payload = null,
            bool useInterface = false,
            CancellationToken cancellationToken = default)
        {
            var host = useInterface ? InterfaceUrl : ApiUrl;
            var fullUrl = host + path;
            var payloadJson = JsonText.Object(payload ?? EmptyPairs());
            var parameters = EapiCrypto.EncryptParams(fullUrl, payloadJson);

            using (var content = new FormUrlEncodedContent(new[]
            {
                new KeyValuePair<string, string>("params", parameters),
            }))
            using (var request = new HttpRequestMessage(HttpMethod.Post, fullUrl) { Content = content })
            {
                ApplyCommonHeaders(request, Ua);
                return await _client.SendAsync(request, cancellationToken).ConfigureAwait(false);
            }
        }

        /// <summary>
        /// 逐行对齐官方客户端（iPhone）的 eapi 写接口请求：设备字段以 Cookie 形式发送，
        /// 同样的 header 也写进加密 body 的 <c>header</c> 字段，用于 <c>/eapi/radio/like</c>
        /// 之类排除 -460 风控字段形态差异。
        /// </summary>
        public async Task<HttpResponseMessage> EapiPostOfficialAsync(
            string path,
            IEnumerable<KeyValuePair<string, string>>? payload = null,
            CancellationToken cancellationToken = default)
        {
            var fullUrl = ApiUrl + path;
            var csrf = GetCsrfToken() ?? string.Empty;
            var mus = ParseCookie(Cookie);

            var header = new List<KeyValuePair<string, string>>
            {
                new KeyValuePair<string, string>("os", "iphone"),
                new KeyValuePair<string, string>("appver", "8.9.60"),
                new KeyValuePair<string, string>("deviceId", mus.TryGetValue("deviceId", out var deviceId) ? deviceId : RandomText.Hex(20)),
                new KeyValuePair<string, string>("osver", mus.TryGetValue("osver", out var osver) ? osver : "16.0"),
                new KeyValuePair<string, string>("versioncode", "140"),
                new KeyValuePair<string, string>("mobilename", string.Empty),
                new KeyValuePair<string, string>("buildver", DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString().Substring(0, 10)),
                new KeyValuePair<string, string>("resolution", "1920x1080"),
                new KeyValuePair<string, string>("__csrf", csrf),
                new KeyValuePair<string, string>("channel", "yykj"),
                new KeyValuePair<string, string>("requestId", RandomText.Next(20_000_000, 30_000_000).ToString()),
            };

            if (mus.TryGetValue("MUSIC_U", out var musicU))
            {
                header.Add(new KeyValuePair<string, string>("MUSIC_U", musicU));
            }

            if (mus.TryGetValue("MUSIC_A", out var musicA))
            {
                header.Add(new KeyValuePair<string, string>("MUSIC_A", musicA));
            }

            var cookieStr = new StringBuilder();
            foreach (var pair in header)
            {
                if (cookieStr.Length > 0)
                {
                    cookieStr.Append(';');
                }

                cookieStr.Append(Uri.EscapeDataString(pair.Key)).Append('=').Append(Uri.EscapeDataString(pair.Value));
            }

            var data = new List<KeyValuePair<string, string>>(payload ?? EmptyPairs())
            {
                new KeyValuePair<string, string>("header", JsonText.Object(header)),
            };
            var parameters = EapiCrypto.EncryptParams(fullUrl, JsonText.Object(data));

            using (var content = new FormUrlEncodedContent(new[]
            {
                new KeyValuePair<string, string>("params", parameters),
            }))
            using (var request = new HttpRequestMessage(HttpMethod.Post, fullUrl) { Content = content })
            {
                request.Headers.TryAddWithoutValidation("User-Agent", IosUa);
                request.Headers.TryAddWithoutValidation("Referer", "https://music.163.com/");
                request.Headers.TryAddWithoutValidation("Cookie", cookieStr.ToString());
                return await _client.SendAsync(request, cancellationToken).ConfigureAwait(false);
            }
        }

        /// <summary>
        /// PC 客户端身份的 eapi POST（打 <see cref="InterfacePcUrl"/>）。扫码登录专用：host、PC UA、
        /// PC 设备字段（同时作为 Cookie）与业务里的 <c>type=3</c> 缺一都拿不到 unikey
        /// （老通道 weapi + <c>type=1</c> 现在直接回 <c>-462</c>，依据见 <c>spec/api/endpoints.md</c>）。
        ///
        /// <paramref name="path"/> 按 <c>/api/…</c> 形态传入（与 <see cref="WeapiPostAsync"/> /
        /// <see cref="XeapiPostAsync"/> 一致），这里改写成 <c>/eapi/…</c>。
        ///
        /// <paramref name="fieldsJson"/> 是业务字段的 JSON **片段**（不含外层大括号），原样拼进信封。
        /// 之所以收文本而不是键值对：参考实现里 <c>type</c> 是数值、<c>e_r</c> 是布尔，收成字符串会
        /// 把 <c>"type":3</c> 变成 <c>"type":"3"</c>。信封字段顺序与参考实现一致：业务字段 →
        /// <c>e_r:false</c> → <c>header</c>。
        /// </summary>
        public async Task<HttpResponseMessage> EapiPostPcAsync(
            string path,
            string fieldsJson,
            CancellationToken cancellationToken = default)
        {
            var header = BuildPcHeader();
            return await EapiPostWithIdentityAsync(
                InterfacePcUrl, PcUa, path, fieldsJson, header, cancellationToken).ConfigureAwait(false);
        }

        /// <summary>
        /// eapi + **os=osx 身份** POST（打 <see cref="ClientLogUrl"/>）。播放上报专用：听歌打卡只有
        /// 「clientlog + eapi + os=osx」这个组合入账，android / iphone / pc 身份的同名请求照回 200
        /// 但会被服务端丢弃（依据 NeteaseCloudMusicApiEnhanced/api-enhanced#167，协议见
        /// <c>spec/api/endpoints.md</c> 的「播放上报 / 听歌打卡」）。请求头只有 UA 与 Cookie
        /// （参考实现这条路径不带 Referer）。
        /// </summary>
        /// <param name="path"><c>/api/…</c> 形态，这里改写成 <c>/eapi/…</c>。</param>
        /// <param name="fieldsJson">业务字段的 JSON 片段（播放上报只有 <c>logs</c>）。</param>
        public async Task<HttpResponseMessage> EapiPostOsxAsync(
            string path,
            string fieldsJson,
            CancellationToken cancellationToken = default)
        {
            var header = BuildOsxHeader();
            return await EapiPostWithIdentityAsync(
                ClientLogUrl, OsxUa, path, fieldsJson, header, cancellationToken).ConfigureAwait(false);
        }

        /// <summary>
        /// eapi + 客户端身份 POST 的公共拼装（PC 扫码 / osx 播报共用）：设备字段按
        /// <see cref="BuildHeaderCookie"/> 拼成 Cookie，同一份再进加密 body 的 <c>header</c>；
        /// 信封是 业务字段 → <c>e_r:false</c> → <c>header</c>。host 与 UA 是身份差异，作参数传入。
        /// </summary>
        private async Task<HttpResponseMessage> EapiPostWithIdentityAsync(
            string host,
            string ua,
            string path,
            string fieldsJson,
            List<KeyValuePair<string, string>> header,
            CancellationToken cancellationToken)
        {
            var fullUrl = host + EapiPath(path);
            var payloadJson = new StringBuilder("{")
                .Append(fieldsJson)
                .Append(",\"e_r\":false,\"header\":")
                .Append(JsonText.Object(header))
                .Append('}')
                .ToString();
            var parameters = EapiCrypto.EncryptParams(fullUrl, payloadJson);

            using (var content = new FormUrlEncodedContent(new[]
            {
                new KeyValuePair<string, string>("params", parameters),
            }))
            using (var request = new HttpRequestMessage(HttpMethod.Post, fullUrl) { Content = content })
            {
                request.Headers.TryAddWithoutValidation("User-Agent", ua);
                request.Headers.TryAddWithoutValidation("Cookie", BuildHeaderCookie(header));
                return await _client.SendAsync(request, cancellationToken).ConfigureAwait(false);
            }
        }

        /// <summary>
        /// PC 客户端设备字段。同一份既写进加密 body 的 <c>header</c>，也拼成请求 Cookie ——
        /// 两处必须逐字段一致，服务端就是靠它把这次扫码绑到一个「PC 客户端会话」上的。
        /// deviceId 复用 xeapi 持久化的那一台设备 id（见 <see cref="XeapiState.DeviceId"/>）。
        /// </summary>
        private List<KeyValuePair<string, string>> BuildPcHeader() => new List<KeyValuePair<string, string>>
        {
            new KeyValuePair<string, string>("osver", "Microsoft-Windows-10-Professional-build-19045-64bit"),
            new KeyValuePair<string, string>("deviceId", Xeapi?.DeviceId ?? FallbackDeviceId()),
            new KeyValuePair<string, string>("os", "pc"),
            new KeyValuePair<string, string>("appver", "3.1.17.204416"),
            new KeyValuePair<string, string>("versioncode", "140"),
            new KeyValuePair<string, string>("mobilename", string.Empty),
            // 参考实现取毫秒时间戳的前 10 位，等价于秒级时间戳。
            new KeyValuePair<string, string>(
                "buildver",
                DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString(CultureInfo.InvariantCulture)),
            new KeyValuePair<string, string>("resolution", "1920x1080"),
            // 扫码时还没登录，参考实现固定发空串。
            new KeyValuePair<string, string>("__csrf", string.Empty),
            new KeyValuePair<string, string>("channel", "netease"),
            new KeyValuePair<string, string>(
                "requestId",
                DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture)
                + "_"
                + RandomText.Next(0, 1000).ToString("D4", CultureInfo.InvariantCulture)),
        };

        /// <summary>
        /// os=osx 身份的设备字段（播放上报专用）。**顺序即协议**：与参考实现 <c>util/request.js</c>
        /// 的 eapi 分支逐项一致（`os=osx` 是入账与否的决定性变量）。同一份既写进加密 body 的
        /// <c>header</c>，也拼成请求 Cookie —— 两处必须逐字段一致。
        /// deviceId 复用 xeapi 持久化的那一台设备 id。
        /// </summary>
        private List<KeyValuePair<string, string>> BuildOsxHeader()
        {
            var header = new List<KeyValuePair<string, string>>
            {
                new KeyValuePair<string, string>("osver", "15.5"),
                new KeyValuePair<string, string>("deviceId", Xeapi?.DeviceId ?? FallbackDeviceId()),
                new KeyValuePair<string, string>("os", "osx"),
                new KeyValuePair<string, string>("appver", "3.1.10.5100"),
                new KeyValuePair<string, string>("versioncode", "140"),
                new KeyValuePair<string, string>("mobilename", string.Empty),
                new KeyValuePair<string, string>(
                    "buildver",
                    DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString(CultureInfo.InvariantCulture)),
                new KeyValuePair<string, string>("resolution", "1920x1080"),
                new KeyValuePair<string, string>("__csrf", GetCsrfToken() ?? string.Empty),
                new KeyValuePair<string, string>("channel", "netease"),
                new KeyValuePair<string, string>(
                    "requestId",
                    DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture)
                    + "_"
                    + RandomText.Next(0, 1000).ToString("D4", CultureInfo.InvariantCulture)),
            };

            // 登录态两个字段按有无追加在末尾（参考实现同序）：匿名请求不带 MUSIC_U 就没有这一项。
            var session = ParseCookie(Cookie);
            if (session.TryGetValue("MUSIC_U", out var musicU))
            {
                header.Add(new KeyValuePair<string, string>("MUSIC_U", musicU));
            }

            if (session.TryGetValue("MUSIC_A", out var musicA))
            {
                header.Add(new KeyValuePair<string, string>("MUSIC_A", musicA));
            }

            return header;
        }

        /// <summary>
        /// 把设备字段拼成请求 Cookie：<c>encodeURIComponent(k)=encodeURIComponent(v)</c> 以 <c>"; "</c> 拼接
        /// （PC / osx 两种身份共用）。<see cref="Uri.EscapeDataString"/> 只对 <c>!*'()</c> 多转义，
        /// 而本方法的取值里不可能出现它们，故与参考实现的 <c>encodeURIComponent</c> 逐字符等价。
        /// </summary>
        private static string BuildHeaderCookie(IEnumerable<KeyValuePair<string, string>> header)
        {
            var sb = new StringBuilder();
            foreach (var pair in header)
            {
                if (sb.Length > 0)
                {
                    sb.Append("; ");
                }

                sb.Append(Uri.EscapeDataString(pair.Key)).Append('=').Append(Uri.EscapeDataString(pair.Value));
            }

            return sb.ToString();
        }

        private static string FallbackDeviceId() =>
            _fallbackDeviceId ??= CryptoUtil.ToHex(CryptoUtil.RandomBytes(16));

        /// <summary>
        /// weapi POST。路径 <c>/api/…</c> 会改写成 <c>/weapi/…</c>；payload 缺
        /// <c>csrf_token</c> 时自动从 Cookie 注入。未登录时不发空的 Cookie 头。
        /// </summary>
        public async Task<HttpResponseMessage> WeapiPostAsync(
            string path,
            string payloadJson,
            string? extraCookies = null,
            CancellationToken cancellationToken = default)
        {
            var csrf = GetCsrfToken();
            var body = string.IsNullOrEmpty(csrf) ? payloadJson : AddStringPropertyIfMissing(payloadJson, "csrf_token", csrf!);

            var (parameters, encSecKey) = WeapiCrypto.EncryptParams(body);
            var weapiPath = path.StartsWith("/api/", StringComparison.Ordinal)
                ? "/weapi/" + path.Substring("/api/".Length)
                : path;
            var fullUrl = BaseUrl + weapiPath;

            using (var content = new FormUrlEncodedContent(new[]
            {
                new KeyValuePair<string, string>("params", parameters),
                new KeyValuePair<string, string>("encSecKey", encSecKey),
            }))
            using (var request = new HttpRequestMessage(HttpMethod.Post, fullUrl) { Content = content })
            {
                request.Headers.TryAddWithoutValidation("User-Agent", Ua);
                request.Headers.TryAddWithoutValidation("Referer", "https://music.163.com/");
                request.Headers.TryAddWithoutValidation("Origin", BaseUrl);

                var cookieHeader = JoinCookies(Cookie, extraCookies);
                if (cookieHeader.Length > 0)
                {
                    request.Headers.TryAddWithoutValidation("Cookie", cookieHeader);
                }

                return await _client.SendAsync(request, cancellationToken).ConfigureAwait(false);
            }
        }

        /// <summary>
        /// xeapi POST —— 官方 Android 9.x 的请求加密层（协议见 <c>spec/api/xeapi.md</c>）。
        /// <paramref name="uri"/> 是 <c>/api/…</c> 形态，会被改写成 <c>/xeapi/…</c> 并打到
        /// <see cref="InterfaceUrl"/>。<paramref name="antiCheat"/> 为 true 时现取易盾 token
        /// （不缓存，取不到发空串，不阻断请求）。
        ///
        /// 返回解密后的 JSON 文本。响应解不开说明公钥或会话已失效：清会话、强制刷新公钥
        /// （带上旧 version）后重试一次，再失败才抛 <see cref="HttpRequestException"/>。
        /// </summary>
        public async Task<string> XeapiPostAsync(
            string uri,
            IEnumerable<KeyValuePair<string, string>>? payload = null,
            bool antiCheat = false,
            CancellationToken cancellationToken = default)
        {
            var state = Xeapi;
            if (state == null)
            {
                throw new InvalidOperationException("未配置 XeapiState，无法发送 xeapi 请求");
            }

            var pairs = new List<KeyValuePair<string, string>>(payload ?? EmptyPairs());
            var keyState = await state
                .EnsureKeyStateAsync(FetchPublicKeyAsync, false, cancellationToken)
                .ConfigureAwait(false);
            if (keyState == null)
            {
                throw new HttpRequestException("取 xeapi 公钥失败");
            }

            for (var attempt = 0; ; attempt++)
            {
                // 每次重试都要新的临时密钥对与 IV：旧的那份已经证明服务端不认。
                var request = XeapiCrypto.EncryptRequest(pairs, keyState, XeapiRandom.Create(), state.Session);
                using (var response = await SendXeapiAsync(uri, request.Body, state, antiCheat, cancellationToken)
                    .ConfigureAwait(false))
                {
                    CaptureSession(state, response);
                    var bytes = await response.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
                    if (XeapiCrypto.TryDecryptResponse(bytes, out var json))
                    {
                        return json;
                    }
                }

                if (attempt >= 1)
                {
                    throw new HttpRequestException("xeapi 响应解密失败（已刷新过一次公钥）");
                }

                state.ClearSession();
                keyState = await state
                    .EnsureKeyStateAsync(FetchPublicKeyAsync, true, cancellationToken)
                    .ConfigureAwait(false);
                if (keyState == null)
                {
                    throw new HttpRequestException("刷新 xeapi 公钥失败");
                }
            }
        }

        public async Task<string> GetAsync(
            string path,
            bool useInterface = false,
            CancellationToken cancellationToken = default)
        {
            var host = useInterface ? InterfaceUrl : BaseUrl;
            using (var request = new HttpRequestMessage(HttpMethod.Get, host + path))
            {
                ApplyCommonHeaders(request, Ua);
                using (var response = await _client.SendAsync(request, cancellationToken).ConfigureAwait(false))
                {
                    return await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                }
            }
        }

        /// <summary>REST 表单 POST（打 <see cref="BaseUrl"/>），用于搜索 / 歌曲详情 / 歌词等明文接口。</summary>
        public async Task<HttpResponseMessage> PostFormAsync(
            string path,
            IEnumerable<KeyValuePair<string, string>> fields,
            CancellationToken cancellationToken = default)
        {
            using (var content = new FormUrlEncodedContent(fields))
            using (var request = new HttpRequestMessage(HttpMethod.Post, BaseUrl + path) { Content = content })
            {
                ApplyCommonHeaders(request, Ua);
                return await _client.SendAsync(request, cancellationToken).ConfigureAwait(false);
            }
        }

        /// <summary>从当前 Cookie 提取 <c>__csrf</c>，供 weapi 与 eapi 身份头使用。</summary>
        public string? GetCsrfToken() => GetCsrfToken(Cookie);

        public static string? GetCsrfToken(string? cookie)
        {
            if (string.IsNullOrEmpty(cookie))
            {
                return null;
            }

            foreach (var part in cookie!.Split(';'))
            {
                var trimmed = part.Trim();
                if (trimmed.StartsWith("__csrf=", StringComparison.Ordinal))
                {
                    return trimmed.Substring("__csrf=".Length);
                }
            }

            return null;
        }

        public void Dispose() => _client.Dispose();

        /// <summary>
        /// 公钥请求（协议 §2）：<b>明文</b>表单打 <c>API_URL</c>，Cookie 只带 deviceId。
        /// 响应必须验签，且缺 <c>sk</c> 视为失败 —— 返回 null 时上层会继续用旧公钥或直接回退老通道。
        /// </summary>
        private async Task<XeapiKeyState?> FetchPublicKeyAsync(string? currentKeyVersion, CancellationToken cancellationToken)
        {
            var state = Xeapi;
            if (state == null)
            {
                return null;
            }

            var nonce = RandomDigits(16);
            var timestamp = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds().ToString(CultureInfo.InvariantCulture);
            var body = XeapiCrypto.FormEncode(new[]
            {
                new KeyValuePair<string, string>("appVersion", "9.5.61"),
                new KeyValuePair<string, string>("currentKeyVersion", currentKeyVersion ?? string.Empty),
                new KeyValuePair<string, string>("deviceId", state.DeviceId),
                new KeyValuePair<string, string>("nonce", nonce),
                new KeyValuePair<string, string>("os", "android"),
                new KeyValuePair<string, string>("requestType", "active"),
                new KeyValuePair<string, string>("signature", XeapiCrypto.Sign(timestamp, nonce)),
                new KeyValuePair<string, string>("t1", string.Empty),
                new KeyValuePair<string, string>("t2", string.Empty),
                new KeyValuePair<string, string>("timestamp", timestamp),
                new KeyValuePair<string, string>("uid", string.Empty),
            });

            try
            {
                using (var content = CreateFormContent(body))
                using (var request = new HttpRequestMessage(HttpMethod.Post, ApiUrl + "/api/bsr/sk/get")
                {
                    Content = content,
                })
                {
                    request.Headers.TryAddWithoutValidation("User-Agent", XeapiCrypto.AndroidUserAgent);
                    request.Headers.TryAddWithoutValidation("Cookie", "deviceId=" + state.DeviceId);
                    using (var response = await _client.SendAsync(request, cancellationToken).ConfigureAwait(false))
                    {
                        var text = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                        return ParsePublicKeyResponse(text, nonce);
                    }
                }
            }
            catch
            {
                return null;
            }
        }

        private static XeapiKeyState? ParsePublicKeyResponse(string text, string nonce)
        {
            JsonValue json;
            try
            {
                json = JsonValue.Parse(text);
            }
            catch (JsonParseException)
            {
                return null;
            }

            if (json.GetInt("code") != 200)
            {
                return null;
            }

            var data = json.GetObject("data");
            var encryptedData = data?.GetString("encryptedData");
            var signature = data?.GetString("signature");
            var timestamp = ReadScalarText(data?["timestamp"]);
            if (data == null || string.IsNullOrEmpty(encryptedData) || string.IsNullOrEmpty(signature) || timestamp == null)
            {
                return null;
            }

            // 公钥是后续所有请求的信任根，签名不符一律丢弃 —— 这一步不能省（协议 §2）。
            if (!string.Equals(XeapiCrypto.Sign(timestamp, nonce), signature, StringComparison.Ordinal))
            {
                return null;
            }

            try
            {
                return XeapiState.ParseKeyState(XeapiCrypto.DecryptPublicKey(encryptedData!));
            }
            catch (FormatException)
            {
                return null;
            }
            catch (CryptographicException)
            {
                return null;
            }
        }

        private async Task<HttpResponseMessage> SendXeapiAsync(
            string uri,
            string body,
            XeapiState state,
            bool antiCheat,
            CancellationToken cancellationToken)
        {
            var buildver = DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString(CultureInfo.InvariantCulture);
            var cookie = SessionCookie.Parse(Cookie);
            var musicU = cookie.MusicU;

            // 同名以后者为准：登录 cookie 原串在前，Android 客户端指纹在后（协议 §3.3）。
            cookie.Set("os", "android")
                .Set("osver", "16")
                .Set("appver", "9.1.65")
                .Set("buildver", buildver)
                .Set("deviceId", state.DeviceId)
                .Set("sDeviceId", state.DeviceId);

            var content = CreateFormContent(body);
            var request = new HttpRequestMessage(HttpMethod.Post, InterfaceUrl + XeapiPath(uri))
            {
                Content = content,
            };

            request.Headers.TryAddWithoutValidation("User-Agent", XeapiCrypto.AndroidUserAgent);
            request.Headers.TryAddWithoutValidation("X-Client-Enc-State", "ENCRYPTED");
            request.Headers.TryAddWithoutValidation("x-aeapi", "true");
            request.Headers.TryAddWithoutValidation("x-deviceid", state.DeviceId);
            request.Headers.TryAddWithoutValidation("x-sdeviceid", state.DeviceId);
            request.Headers.TryAddWithoutValidation("x-os", "android");
            request.Headers.TryAddWithoutValidation("x-osver", "16");
            request.Headers.TryAddWithoutValidation("x-appver", "9.1.65");
            request.Headers.TryAddWithoutValidation("x-buildver", buildver);
            if (!string.IsNullOrEmpty(musicU))
            {
                request.Headers.TryAddWithoutValidation("x-music-u", musicU);
            }

            if (antiCheat)
            {
                request.Headers.TryAddWithoutValidation(
                    "X-antiCheatToken",
                    await FetchAntiCheatTokenAsync(cancellationToken).ConfigureAwait(false));
            }

            request.Headers.TryAddWithoutValidation("Cookie", cookie.ToString());

            using (request)
            {
                return await _client.SendAsync(request, cancellationToken).ConfigureAwait(false);
            }
        }

        /// <summary>易盾反作弊 token（协议 §5）：<c>GET ac.dun.163yun.com/v3/b</c>，每次写入现取、不缓存。</summary>
        private async Task<string> FetchAntiCheatTokenAsync(CancellationToken cancellationToken)
        {
            try
            {
                using (var request = new HttpRequestMessage(
                    HttpMethod.Get, "https://ac.dun.163yun.com/v3/b?pn=YD00000558929251"))
                {
                    request.Headers.TryAddWithoutValidation("User-Agent", XeapiCrypto.AndroidUserAgent);
                    using (var response = await _client.SendAsync(request, cancellationToken).ConfigureAwait(false))
                    {
                        var text = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                        return ParseAntiCheatToken(text);
                    }
                }
            }
            catch
            {
                // 取不到就发空串（上游同样处理），不阻断写入。
                return string.Empty;
            }
        }

        private static string ParseAntiCheatToken(string text)
        {
            // 形如 null([200,<n>,"<token>"])
            var open = text.IndexOf('(');
            var close = text.LastIndexOf(')');
            if (open < 0 || close <= open)
            {
                return string.Empty;
            }

            JsonValue json;
            try
            {
                json = JsonValue.Parse(text.Substring(open + 1, close - open - 1));
            }
            catch (JsonParseException)
            {
                return string.Empty;
            }

            if (!json.IsArray || json.Count < 3 || json[0] == null || !json[0]!.IsNumber || json[0]!.LongValue != 200)
            {
                return string.Empty;
            }

            return json[2]?.StringValue ?? string.Empty;
        }

        private static void CaptureSession(XeapiState state, HttpResponseMessage response)
        {
            var id = FirstHeaderValue(response, "x-encr-ssid");
            var key = FirstHeaderValue(response, "x-encr-sskey");
            if (!string.IsNullOrEmpty(id) && !string.IsNullOrEmpty(key))
            {
                state.Session = new XeapiSession(id!, key!);
            }
        }

        private static string? FirstHeaderValue(HttpResponseMessage response, string name)
        {
            if (!response.Headers.TryGetValues(name, out var values))
            {
                return null;
            }

            foreach (var value in values)
            {
                return value;
            }

            return null;
        }

        private static string XeapiPath(string uri) =>
            uri.StartsWith("/api/", StringComparison.Ordinal) ? "/xeapi/" + uri.Substring(5) : uri;

        private static string EapiPath(string uri) =>
            uri.StartsWith("/api/", StringComparison.Ordinal) ? "/eapi/" + uri.Substring(5) : uri;

        /// <summary>
        /// 请求体在 <see cref="XeapiCrypto.FormEncode(IEnumerable{KeyValuePair{string, string}})"/> 里
        /// 已经编码好，这里只能用 <see cref="StringContent"/>；用 <c>FormUrlEncodedContent</c> 会把
        /// <c>%</c> 再转义一次。
        /// </summary>
        private static StringContent CreateFormContent(string body)
        {
            var content = new StringContent(body, Encoding.UTF8);
            content.Headers.ContentType = new MediaTypeHeaderValue("application/x-www-form-urlencoded")
            {
                CharSet = "utf-8",
            };
            return content;
        }

        /// <summary>16 位十进制随机数，与官方客户端的 nonce 形态一致。</summary>
        private static string RandomDigits(int length)
        {
            var bytes = CryptoUtil.RandomBytes(length);
            var sb = new StringBuilder(length);
            foreach (var b in bytes)
            {
                sb.Append((char)('0' + (b % 10)));
            }

            return sb.ToString();
        }

        /// <summary>JSON 里同一字段可能是字符串也可能是数字，统一取文本。</summary>
        private static string? ReadScalarText(JsonValue? value)
        {
            if (value == null)
            {
                return null;
            }

            if (value.IsString)
            {
                return value.StringValue;
            }

            return value.IsNumber ? value.LongValue.ToString(CultureInfo.InvariantCulture) : null;
        }

        private void ApplyCommonHeaders(HttpRequestMessage request, string userAgent)
        {
            request.Headers.TryAddWithoutValidation("User-Agent", userAgent);
            request.Headers.TryAddWithoutValidation("Referer", "https://music.163.com/");
            if (!string.IsNullOrEmpty(Cookie))
            {
                request.Headers.TryAddWithoutValidation("Cookie", Cookie);
            }
        }

        private static IEnumerable<KeyValuePair<string, string>> EmptyPairs()
        {
            yield break;
        }

        private static Dictionary<string, string> ParseCookie(string? cookie)
        {
            var result = new Dictionary<string, string>(StringComparer.Ordinal);
            if (string.IsNullOrEmpty(cookie))
            {
                return result;
            }

            foreach (var part in cookie!.Split(';'))
            {
                var trimmed = part.Trim();
                var eq = trimmed.IndexOf('=');
                if (eq <= 0)
                {
                    continue;
                }

                result[trimmed.Substring(0, eq).Trim()] = trimmed.Substring(eq + 1);
            }

            return result;
        }

        private static string JoinCookies(string? primary, string? extra)
        {
            var parts = new List<string>(2);
            if (!string.IsNullOrWhiteSpace(primary))
            {
                parts.Add(primary!);
            }

            if (!string.IsNullOrWhiteSpace(extra))
            {
                parts.Add(extra!);
            }

            return string.Join("; ", parts);
        }

        /// <summary>仅当对象里还没有该字符串属性时插入（payload 都是扁平对象）。</summary>
        internal static string AddStringPropertyIfMissing(string json, string name, string value)
        {
            var trimmed = json.Trim();
            if (trimmed.Length < 2 || trimmed[0] != '{' || trimmed[trimmed.Length - 1] != '}')
            {
                return json;
            }

            if (trimmed.IndexOf("\"" + name + "\"", StringComparison.Ordinal) >= 0)
            {
                return json;
            }

            var inner = trimmed.Substring(1, trimmed.Length - 2).Trim();
            var property = "\"" + name + "\":" + JsonText.Escape(value);
            return inner.Length == 0 ? "{" + property + "}" : "{" + inner + "," + property + "}";
        }
    }
}
