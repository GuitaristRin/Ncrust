using System;
using System.Collections.Generic;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;
using Ncrust.Core.Net.Crypto;

namespace Ncrust.Core.Api
{
    /// <summary>
    /// 云收藏的写操作（对应 Android <c>PlaylistApi.likeSong / subAlbum</c>）。
    /// 成功与否只由业务码 == 200 判定。
    /// </summary>
    public sealed class LibraryApi
    {
        private const string LikePath = "/eapi/radio/like";
        private const string SubPath = "/eapi/album/sub";
        private const string UnsubPath = "/eapi/album/unsub";

        private readonly NcmHttp _http;

        public LibraryApi(NcmHttp http)
        {
            _http = http ?? throw new ArgumentNullException(nameof(http));
        }

        /// <summary>
        /// 收藏 / 取消收藏单曲。
        ///
        /// 主通道走与官方网页 / 参考实现完全一致的 canonical weapi <c>/api/radio/like</c>：
        /// <c>alg=itembased&amp;trackId=&lt;id&gt;&amp;like=&lt;bool&gt;&amp;time=3</c>——<c>time</c> 是
        /// 试听秒数（官方固定 3）。旧实现把它当成 Unix 秒级时间戳（约 1.7e9）发送，服务端风控会先
        /// 接受、随后在例行审核里把该 like 判为异常并回滚，表现为「当天可见、隔天消失」。weapi 被
        /// 账号级风控（-460 等）拦截时才回退到 eapi 客户端协议，回退同样使用 <c>time=3</c>。
        /// </summary>
        public async Task<bool> LikeSongAsync(long songId, bool like, CancellationToken cancellationToken = default)
        {
            if (await TryWeapiLikeAsync(songId, like, cancellationToken).ConfigureAwait(false) == 200)
            {
                return true;
            }

            return await TryEapiLikeAsync(songId, like, cancellationToken).ConfigureAwait(false) == 200;
        }

        private async Task<int> TryWeapiLikeAsync(long songId, bool like, CancellationToken cancellationToken)
        {
            var payload = "{\"alg\":\"itembased\",\"trackId\":" + songId +
                          ",\"like\":" + (like ? "true" : "false") + ",\"time\":\"3\"}";
            try
            {
                using (var response = await _http
                    .WeapiPostAsync("/api/radio/like", payload, null, cancellationToken)
                    .ConfigureAwait(false))
                {
                    var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                    return ReadCode(body);
                }
            }
            catch
            {
                return -1;
            }
        }

        private async Task<int> TryEapiLikeAsync(long songId, bool like, CancellationToken cancellationToken)
        {
            var payload = new[]
            {
                new KeyValuePair<string, string>("alg", "itembased"),
                new KeyValuePair<string, string>("trackId", songId.ToString()),
                new KeyValuePair<string, string>("like", like ? "true" : "false"),
                new KeyValuePair<string, string>("time", "3"),
                new KeyValuePair<string, string>("e_r", "TRUE"),
                new KeyValuePair<string, string>("csrf_token", _http.GetCsrfToken() ?? string.Empty),
            };

            try
            {
                using (var response = await _http
                    .EapiPostOfficialAsync(LikePath, payload, cancellationToken)
                    .ConfigureAwait(false))
                {
                    var raw = await response.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
                    var decrypted = EapiCrypto.DecryptResponse(Convert.ToBase64String(raw));
                    var text = string.IsNullOrEmpty(decrypted) ? Encoding.UTF8.GetString(raw) : decrypted;
                    return ReadCode(text);
                }
            }
            catch
            {
                return -1;
            }
        }

        /// <summary>收藏 / 取消收藏专辑（与收藏单曲解耦）。</summary>
        public async Task<bool> SubscribeAlbumAsync(long albumId, bool subscribe, CancellationToken cancellationToken = default)
        {
            var path = subscribe ? SubPath : UnsubPath;
            using (var response = await _http.EapiPostAsync(path, new[]
            {
                new KeyValuePair<string, string>("id", albumId.ToString()),
            }, useInterface: false, cancellationToken).ConfigureAwait(false))
            {
                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                return ReadCode(body) == 200;
            }
        }

        private static int ReadCode(string text)
        {
            try
            {
                return JsonValue.Parse(text).GetInt("code", -1);
            }
            catch (JsonParseException)
            {
                return -1;
            }
        }
    }
}
