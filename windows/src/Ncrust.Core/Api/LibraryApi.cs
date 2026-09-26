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
        /// 收藏 / 取消收藏单曲。走官方客户端指纹的 eapi 写接口，响应可能是 AES-ECB 加密 JSON，
        /// 先尝试解密再用明文兜底。
        /// </summary>
        public async Task<bool> LikeSongAsync(long songId, bool like, CancellationToken cancellationToken = default)
        {
            var payload = new[]
            {
                new KeyValuePair<string, string>("alg", "itembased"),
                new KeyValuePair<string, string>("trackId", songId.ToString()),
                new KeyValuePair<string, string>("like", like ? "true" : "false"),
                new KeyValuePair<string, string>("time", DateTimeOffset.UtcNow.ToUnixTimeSeconds().ToString()),
                new KeyValuePair<string, string>("e_r", "TRUE"),
                new KeyValuePair<string, string>("csrf_token", _http.GetCsrfToken() ?? string.Empty),
            };

            using (var response = await _http
                .EapiPostOfficialAsync(LikePath, payload, cancellationToken)
                .ConfigureAwait(false))
            {
                var raw = await response.Content.ReadAsByteArrayAsync().ConfigureAwait(false);
                var decrypted = EapiCrypto.DecryptResponse(Convert.ToBase64String(raw));
                var text = string.IsNullOrEmpty(decrypted) ? Encoding.UTF8.GetString(raw) : decrypted;
                return ReadCode(text) == 200;
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
