using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;

namespace Ncrust.Core.Api
{
    /// <summary>专辑详情（对应 Android <c>NcmApi.getAlbumDetail</c>，REST GET）。</summary>
    public sealed class AlbumApi
    {
        private readonly NcmHttp _http;

        public AlbumApi(NcmHttp http)
        {
            _http = http ?? throw new ArgumentNullException(nameof(http));
        }

        public async Task<AlbumDetailResult> GetAlbumDetailAsync(long albumId, CancellationToken cancellationToken = default)
        {
            var body = await _http
                .GetAsync("/api/v1/album/" + albumId, useInterface: false, cancellationToken)
                .ConfigureAwait(false);
            var json = JsonValue.Parse(body);

            var albumJson = json.GetObject("album");
            var songsJson = json.GetArray("songs");
            var songs = songsJson == null
                ? (IReadOnlyList<SongItem>)Array.Empty<SongItem>()
                : songsJson.Select(SongItem.FromJson).ToList();

            return new AlbumDetailResult(albumJson == null ? null : AlbumDetail.FromJson(albumJson), songs);
        }

        /// <summary>
        /// 云端「我收藏的专辑」/eapi/album/sublist。eapi 的 data 直接是专辑数组，
        /// 老结构是 <c>data.albums</c>，都兜底。
        /// </summary>
        public async Task<IReadOnlyList<CloudAlbum>> GetSubscribedAlbumsAsync(
            int limit = 100,
            int offset = 0,
            CancellationToken cancellationToken = default)
        {
            using (var response = await _http.EapiPostAsync("/eapi/album/sublist", new[]
            {
                new KeyValuePair<string, string>("limit", limit.ToString()),
                new KeyValuePair<string, string>("offset", offset.ToString()),
                new KeyValuePair<string, string>("total", "true"),
            }, useInterface: false, cancellationToken).ConfigureAwait(false))
            {
                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                var json = JsonValue.Parse(body);
                var albums = json.GetArray("data") ?? json.GetObject("data")?.GetArray("albums");
                if (albums == null)
                {
                    return Array.Empty<CloudAlbum>();
                }

                var result = new List<CloudAlbum>(albums.Count);
                foreach (var album in albums)
                {
                    result.Add(CloudAlbum.FromJson(album));
                }

                return result;
            }
        }
    }
}
