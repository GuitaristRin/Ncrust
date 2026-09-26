using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;

namespace Ncrust.Core.Api
{
    /// <summary>
    /// 首页发现相关的端点（对应 Android <c>PlaylistApi</c> 的 Discovery 段）。
    /// 结构缺失时返回空列表；网络 / 解析异常照常抛出，由上层决定加载态。
    /// </summary>
    public sealed class DiscoveryApi
    {
        private readonly NcmHttp _http;

        public DiscoveryApi(NcmHttp http)
        {
            _http = http ?? throw new ArgumentNullException(nameof(http));
        }

        /// <summary>每日推荐歌曲。/eapi/v2/discovery/recommend/songs，登录后可用。</summary>
        public async Task<IReadOnlyList<SongItem>> GetDailyRecommendSongsAsync(CancellationToken cancellationToken = default)
        {
            var json = await EapiJsonAsync("/eapi/v2/discovery/recommend/songs", cancellationToken).ConfigureAwait(false);
            var songs = json.GetArray("recommend") ?? json.GetArray("data");
            return ToSongList(songs);
        }

        /// <summary>推荐歌单 /eapi/v1/discovery/recommend/resource，取前 10 个。</summary>
        public async Task<IReadOnlyList<PlaylistCard>> GetRecommendPlaylistsAsync(CancellationToken cancellationToken = default)
        {
            var json = await EapiJsonAsync("/eapi/v1/discovery/recommend/resource", cancellationToken).ConfigureAwait(false);
            var items = json.GetArray("recommend");
            if (items == null)
            {
                return Array.Empty<PlaylistCard>();
            }

            var result = new List<PlaylistCard>();
            var count = Math.Min(items.Count, 10);
            for (var i = 0; i < count; i++)
            {
                result.Add(PlaylistCard.FromJson(items[i]));
            }

            return result;
        }

        /// <summary>新歌速递 /api/v1/discovery/new/songs（REST GET）。</summary>
        public async Task<IReadOnlyList<SongItem>> GetTopSongsAsync(
            int limit = 30,
            int offset = 0,
            CancellationToken cancellationToken = default)
        {
            var body = await _http
                .GetAsync($"/api/v1/discovery/new/songs?limit={limit}&offset={offset}", false, cancellationToken)
                .ConfigureAwait(false);
            var json = JsonValue.Parse(body);
            var songs = json.GetArray("data") ?? json.GetArray("songs");
            return ToSongList(songs);
        }

        /// <summary>
        /// 私人 FM /eapi/v1/radio/get。每项是 <c>{ "song": {...} }</c>，个别响应把歌曲
        /// 摊在顶层，这里都兜底。
        /// </summary>
        public async Task<IReadOnlyList<SongItem>> GetPersonalFmAsync(CancellationToken cancellationToken = default)
        {
            var json = await EapiJsonAsync("/eapi/v1/radio/get", cancellationToken).ConfigureAwait(false);
            var items = json.GetArray("data");
            if (items == null)
            {
                return Array.Empty<SongItem>();
            }

            var result = new List<SongItem>(items.Count);
            foreach (var item in items)
            {
                result.Add(SongItem.FromJson(item.GetObject("song") ?? item));
            }

            return result;
        }

        private async Task<JsonValue> EapiJsonAsync(string path, CancellationToken cancellationToken)
        {
            using (var response = await _http.EapiPostAsync(path, null, false, cancellationToken).ConfigureAwait(false))
            {
                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                return JsonValue.Parse(body);
            }
        }

        private static IReadOnlyList<SongItem> ToSongList(IReadOnlyList<JsonValue>? items)
        {
            if (items == null)
            {
                return Array.Empty<SongItem>();
            }

            var result = new List<SongItem>(items.Count);
            foreach (var item in items)
            {
                result.Add(SongItem.FromJson(item));
            }

            return result;
        }
    }
}
