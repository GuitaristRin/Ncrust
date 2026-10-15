using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;

namespace Ncrust.Core.Auth
{
    /// <summary>一次二维码登录会话的 key。</summary>
    public sealed class QrLoginKey
    {
        public QrLoginKey(string unikey, string qrUrl, string? qrImage)
        {
            Unikey = unikey;
            QrUrl = qrUrl;
            QrImage = qrImage;
        }

        public string Unikey { get; }

        /// <summary>
        /// 二维码内容：<c>https://music.163.com/login?codekey=&lt;unikey&gt;</c>。
        /// PC 客户端身份下**不带** <c>chainId</c> —— 那是 web 登录会话的绑定字段，带上反而不被认。
        /// </summary>
        public string QrUrl { get; }

        /// <summary>服务端给的 base64 PNG；通常为空，由客户端本地渲染。</summary>
        public string? QrImage { get; }
    }

    /// <summary>轮询结果：<see cref="Code"/> 800 过期 / 801 待扫 / 802 已扫待确认 / 803 成功。</summary>
    public readonly struct QrLoginPollResult
    {
        public QrLoginPollResult(int code, string? cookie)
        {
            Code = code;
            Cookie = cookie;
        }

        public int Code { get; }

        /// <summary>成功（803）时从 Set-Cookie 拼出的完整会话 cookie；否则为 null。</summary>
        public string? Cookie { get; }
    }

    /// <summary>
    /// 二维码登录（对应 Android <c>PlaylistApi.getLoginQrKey / checkLoginQr</c> 与
    /// <c>QrLoginDialog</c> 的状态机）。轮询节奏与次数由调用方控制：每 2s 一次、最多 150 次。
    /// 单次轮询失败由调用方跳过重试，这里只返回结果。
    ///
    /// 通道是 eapi + PC 客户端身份 + <c>type=3</c>（打 interfacepc，见
    /// <see cref="NcmHttp.EapiPostPcAsync"/>）：weapi 的 <c>type=1</c> 形态实测回 <c>-462</c>
    /// 风控，二维码出不来。参考实现见 <c>spec/api/endpoints.md</c> 的「eapi · PC 客户端指纹」。
    /// </summary>
    public sealed class QrLoginClient
    {
        public const int Expired = 800;
        public const int Waiting = 801;
        public const int Scanned = 802;
        public const int Succeeded = 803;

        private const string UnikeyPath = "/api/login/qrcode/unikey";
        private const string ClientLoginPath = "/api/login/qrcode/client/login";

        private readonly NcmHttp _http;

        public QrLoginClient(NcmHttp http)
        {
            _http = http ?? throw new ArgumentNullException(nameof(http));
        }

        /// <summary>申请二维码 key。失败返回 null。</summary>
        public async Task<QrLoginKey?> RequestKeyAsync(CancellationToken cancellationToken = default)
        {
            using (var response = await _http
                .EapiPostPcAsync(UnikeyPath, "\"type\":3", cancellationToken)
                .ConfigureAwait(false))
            {
                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                JsonValue json;
                try
                {
                    json = JsonValue.Parse(body);
                }
                catch (JsonParseException)
                {
                    return null;
                }

                if (json.GetInt("code", -1) != 200)
                {
                    return null;
                }

                var unikey = json.GetString("unikey");
                if (string.IsNullOrEmpty(unikey))
                {
                    return null;
                }

                var qrUrl = "https://music.163.com/login?codekey=" + unikey;
                var qrImage = json.GetString("qrimg");
                return new QrLoginKey(unikey!, qrUrl, string.IsNullOrEmpty(qrImage) ? null : qrImage);
            }
        }

        /// <summary>查询扫码状态。<paramref name="key"/> 是 <see cref="QrLoginKey.Unikey"/>。</summary>
        public async Task<QrLoginPollResult> PollAsync(
            string key,
            CancellationToken cancellationToken = default)
        {
            var fieldsJson = "\"key\":" + JsonText.Escape(key) + ",\"type\":3";

            using (var response = await _http
                .EapiPostPcAsync(ClientLoginPath, fieldsJson, cancellationToken)
                .ConfigureAwait(false))
            {
                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                int code;
                try
                {
                    code = JsonValue.Parse(body).GetInt("code", -1);
                }
                catch (JsonParseException)
                {
                    code = -1;
                }

                return new QrLoginPollResult(code, code == Succeeded ? ExtractSessionCookie(response) : null);
            }
        }

        /// <summary>
        /// 从 Set-Cookie 头拼出会话 cookie 串；只在含非空 <c>MUSIC_U</c> 时才算拿到登录态
        /// （803 也可能只给删除写法 <c>MUSIC_U=</c>，旧实现按子串匹配会把空值当成登录成功）。
        /// </summary>
        internal static string? ExtractSessionCookie(HttpResponseMessage response)
        {
            if (!response.Headers.TryGetValues("Set-Cookie", out var values))
            {
                return null;
            }

            var parts = new List<string>();
            foreach (var value in values)
            {
                var semicolon = value.IndexOf(';');
                var pair = (semicolon >= 0 ? value.Substring(0, semicolon) : value).Trim();
                if (pair.Contains("="))
                {
                    parts.Add(pair);
                }
            }

            // 同一响应里可能有两条同名 MUSIC_U（删除用的空值与真值，顺序不定）：按 Set-Cookie
            // 语义解析并取最后一个非空值；顺带去重，别把重复名字交给服务端。
            var cookie = SessionCookie.ParseSetCookie(string.Join("; ", parts));
            return cookie.HasMusicU ? cookie.ToString() : null;
        }
    }
}
