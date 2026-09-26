using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;

namespace Ncrust.Core.Api
{
    /// <summary>歌手专辑列表（对应 Android <c>NcmApi.getArtistAlbums</c>，REST GET）。</summary>
    public sealed class ArtistApi
    {
        private readonly NcmHttp _http;

        public ArtistApi(NcmHttp http)
        {
            _http = http ?? throw new ArgumentNullException(nameof(http));
        }

        public async Task<ArtistAlbumsResult> GetArtistAlbumsAsync(
            long artistId,
            int limit = 50,
            int offset = 0,
            CancellationToken cancellationToken = default)
        {
            var body = await _http
                .GetAsync($"/api/artist/albums/{artistId}?limit={limit}&offset={offset}", false, cancellationToken)
                .ConfigureAwait(false);
            var json = JsonValue.Parse(body);

            var artistJson = json.GetObject("artist");
            var albumsJson = json.GetArray("hotAlbums");
            var albums = new List<ArtistAlbumItem>();
            if (albumsJson != null)
            {
                foreach (var album in albumsJson)
                {
                    albums.Add(ArtistAlbumItem.FromJson(album));
                }
            }

            return new ArtistAlbumsResult(
                artistJson == null ? null : ArtistDetail.FromJson(artistJson),
                albums,
                json.GetBool("more"));
        }
    }
}
