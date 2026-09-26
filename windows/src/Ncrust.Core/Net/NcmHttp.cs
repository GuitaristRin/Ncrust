using System;
using System.Collections.Generic;
using System.Net;
using System.Net.Http;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
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

        private const string Ua =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

        private const string IosUa =
            "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1";

        private readonly HttpClient _client;
        private readonly bool _ownsHandler;

        /// <summary>当前登录 Cookie 原串。未登录时为 null。</summary>
        public string? Cookie { get; set; }

        public NcmHttp(HttpMessageHandler? handler = null)
        {
            _ownsHandler = handler == null;
            _client = new HttpClient(handler ?? CreateHandler(), _ownsHandler)
            {
                Timeout = TimeSpan.FromSeconds(30),
            };
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

        /// <summary>播放上报（webLog）：普通表单 POST <c>logs=&lt;JSON&gt;</c>，不走 eapi/weapi 加密。</summary>
        public async Task<HttpResponseMessage> PostWeblogAsync(
            string weblogUrl,
            string logsJson,
            CancellationToken cancellationToken = default)
        {
            using (var content = new FormUrlEncodedContent(new[]
            {
                new KeyValuePair<string, string>("logs", logsJson),
            }))
            using (var request = new HttpRequestMessage(HttpMethod.Post, weblogUrl) { Content = content })
            {
                ApplyCommonHeaders(request, Ua);
                return await _client.SendAsync(request, cancellationToken).ConfigureAwait(false);
            }
        }

        /// <summary>从当前 Cookie 提取 <c>__csrf</c>，供 weapi 与 webLog 使用。</summary>
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
