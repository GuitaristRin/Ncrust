using System;
using System.Collections.Generic;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;
using Ncrust.Core.Util;

namespace Ncrust.Core.Auth
{
    /// <summary>一次二维码登录会话的 key。</summary>
    public sealed class QrLoginKey
    {
        public QrLoginKey(string unikey, string qrUrl, string? qrImage, string sDeviceId)
        {
            Unikey = unikey;
            QrUrl = qrUrl;
            QrImage = qrImage;
            SDeviceId = sDeviceId;
        }

        public string Unikey { get; }

        /// <summary>二维码内容：带 chainId 的官方登录链接，官方 App 据此把确认绑到本会话。</summary>
        public string QrUrl { get; }

        /// <summary>服务端给的 base64 PNG；通常为空，由客户端本地渲染。</summary>
        public string? QrImage { get; }

        /// <summary>与 chainId / 轮询 Cookie 必须一致的 web 设备 id。</summary>
        public string SDeviceId { get; }
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
                .WeapiPostAsync(UnikeyPath, "{\"type\":1,\"noCheckToken\":true}", null, cancellationToken)
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

                var sDeviceId = RandomText.SDeviceId();
                var chainId = "v1_" + sDeviceId + "_web_login_" + DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
                var qrUrl = "https://music.163.com/login?codekey=" + unikey + "&chainId=" + chainId;
                var qrImage = json.GetString("qrimg");
                return new QrLoginKey(unikey!, qrUrl, string.IsNullOrEmpty(qrImage) ? null : qrImage, sDeviceId);
            }
        }

        /// <summary>查询扫码状态。</summary>
        public async Task<QrLoginPollResult> PollAsync(
            string key,
            string sDeviceId,
            CancellationToken cancellationToken = default)
        {
            var payload = "{\"type\":1,\"noCheckToken\":true,\"key\":" + JsonText.Escape(key) + "}";
            var extraCookies = "os=pc; NMTID=" + RandomText.UpperHex(16) + "; sDeviceId=" + sDeviceId;

            using (var response = await _http
                .WeapiPostAsync(ClientLoginPath, payload, extraCookies, cancellationToken)
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

        /// <summary>从 Set-Cookie 头拼出会话 cookie 串；只在含 <c>MUSIC_U=</c> 时有效。</summary>
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

            var cookie = string.Join("; ", parts);
            return cookie.Contains("MUSIC_U=") ? cookie : null;
        }
    }
}
