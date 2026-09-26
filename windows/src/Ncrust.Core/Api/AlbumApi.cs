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
    }
}
