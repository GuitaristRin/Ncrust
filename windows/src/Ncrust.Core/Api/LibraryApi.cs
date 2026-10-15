using System;
using System.Collections.Generic;
using System.Globalization;
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
        private const string XeapiLikePath = "/api/v1/radio/like";
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
        /// 主通道走官方 Android 9.x 的 xeapi <c>/api/v1/radio/like</c>：
        /// <c>alg=itembased&amp;trackId=&lt;id&gt;&amp;like=&lt;bool&gt;&amp;time=3</c>，带易盾 token。
        /// 老通道（weapi / eapi 的 <c>/api/radio/like</c>）会被服务端风控**事后回滚**：
        /// 收藏当天在官方与 Ncrust 两端都可见，例行审核时被取消，表现为「隔天消失」。
        /// gmg137/netease-cloud-music-gtk#410 实测老通道会返回 524「当前环境异常，已取消喜欢」，
        /// 改走 xeapi 后修复；Ncrust 的 de193ff / 25f0c39 只改了 <c>time</c>（时间戳 → 3）
        /// 仍复现，说明 <c>time</c> 不是诱因，通道才是。结论与取证见 <c>spec/api/xeapi.md</c> 末节。
        ///
        /// weapi / eapi 保留为回退：xeapi 不可用（未配置状态、公钥取不到、网络异常、非 200）时
        /// 依次尝试，但它们写进去的收藏仍可能被回滚，只是不让收藏功能整体不可用。
        /// </summary>
        public async Task<bool> LikeSongAsync(long songId, bool like, CancellationToken cancellationToken = default)
        {
            if (await TryXeapiLikeAsync(songId, like, cancellationToken).ConfigureAwait(false) == 200)
            {
                return true;
            }

            if (await TryWeapiLikeAsync(songId, like, cancellationToken).ConfigureAwait(false) == 200)
            {
                return true;
            }

            return await TryEapiLikeAsync(songId, like, cancellationToken).ConfigureAwait(false) == 200;
        }

        private async Task<int> TryXeapiLikeAsync(long songId, bool like, CancellationToken cancellationToken)
        {
            var pairs = new[]
            {
                new KeyValuePair<string, string>("alg", "itembased"),
                new KeyValuePair<string, string>("trackId", songId.ToString(CultureInfo.InvariantCulture)),
                new KeyValuePair<string, string>("like", like ? "true" : "false"),
                new KeyValuePair<string, string>("time", "3"),
            };

            try
            {
                var json = await _http
                    .XeapiPostAsync(XeapiLikePath, pairs, antiCheat: true, cancellationToken)
                    .ConfigureAwait(false);
                return ReadCode(json);
            }
            catch
            {
                return -1;
            }
        }

        private async Task<int> TryWeapiLikeAsync(long songId, bool like, CancellationToken cancellationToken)
        {
            // 回退通道：服务端风控会把这里写下的 like 事后回滚（见 LikeSongAsync 的说明），
            // 只在 xeapi 不可用时用。
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
            // 同 weapi：回退通道，写入可能被事后回滚。
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
