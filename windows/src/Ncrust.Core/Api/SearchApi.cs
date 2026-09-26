using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;

namespace Ncrust.Core.Api
{
    /// <summary>
    /// 三类搜索（对应 Android <c>NcmApi.search / searchAlbum / searchArtist</c>）：
    /// REST 表单 POST <c>/api/cloudsearch/pc</c>，type 1 / 10 / 100。
    /// </summary>
    public sealed class SearchApi
    {
        private readonly NcmHttp _http;

        public SearchApi(NcmHttp http)
        {
            _http = http ?? throw new ArgumentNullException(nameof(http));
        }

        public async Task<IReadOnlyList<SongItem>> SearchSongsAsync(
            string keyword,
            int limit = 30,
            CancellationToken cancellationToken = default)
        {
            var result = await SearchAsync(keyword, 1, limit, cancellationToken).ConfigureAwait(false);
            return ToSongs(result?.GetArray("songs"));
        }

        public async Task<IReadOnlyList<AlbumSearchItem>> SearchAlbumsAsync(
            string keyword,
            int limit = 30,
            CancellationToken cancellationToken = default)
        {
            var result = await SearchAsync(keyword, 10, limit, cancellationToken).ConfigureAwait(false);
            var albums = result?.GetArray("albums");
            if (albums == null)
            {
                return Array.Empty<AlbumSearchItem>();
            }

            var items = new List<AlbumSearchItem>(albums.Count);
            foreach (var album in albums)
            {
                items.Add(AlbumSearchItem.FromJson(album));
            }

            return items;
        }

        public async Task<IReadOnlyList<ArtistSearchItem>> SearchArtistsAsync(
            string keyword,
            int limit = 30,
            CancellationToken cancellationToken = default)
        {
            var result = await SearchAsync(keyword, 100, limit, cancellationToken).ConfigureAwait(false);
            var artists = result?.GetArray("artists");
            if (artists == null)
            {
                return Array.Empty<ArtistSearchItem>();
            }

            var items = new List<ArtistSearchItem>(artists.Count);
            foreach (var artist in artists)
            {
                items.Add(ArtistSearchItem.FromJson(artist));
            }

            return items;
        }

        private async Task<JsonValue?> SearchAsync(string keyword, int type, int limit, CancellationToken cancellationToken)
        {
            using (var response = await _http.PostFormAsync("/api/cloudsearch/pc", new[]
            {
                new KeyValuePair<string, string>("s", keyword),
                new KeyValuePair<string, string>("type", type.ToString()),
                new KeyValuePair<string, string>("limit", limit.ToString()),
            }, cancellationToken).ConfigureAwait(false))
            {
                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                return JsonValue.Parse(body).GetObject("result");
            }
        }

        private static IReadOnlyList<SongItem> ToSongs(IReadOnlyList<JsonValue>? songs)
        {
            if (songs == null)
            {
                return Array.Empty<SongItem>();
            }

            var items = new List<SongItem>(songs.Count);
            foreach (var song in songs)
            {
                items.Add(SongItem.FromJson(song));
            }

            return items;
        }
    }
}
