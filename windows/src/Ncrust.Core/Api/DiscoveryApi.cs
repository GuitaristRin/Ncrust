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
            return ToSongList(songs, json.GetArray("privileges"));
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
            return ToSongList(songs, json.GetArray("privileges"));
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

            // 权益与歌曲并列返回；按 id 关联后，取链判定「无版权」才有依据（否则只能说「暂时无法播放」）。
            SongItem.AttachPrivileges(result, json.GetArray("privileges"));
            return result;
        }

        /// <summary>
        /// 相似歌曲（INFINITY 续播的种子）。主路径 eapi <c>/eapi/v1/discovery/similarSong</c>，
        /// 为空或失败时回退 weapi <c>/api/discovery/simiSong</c>；两路都失败返回空列表
        /// （对应 Android <c>getSimilarSongs</c> 的两段 runCatching）。取消照常抛出。
        /// </summary>
        public async Task<IReadOnlyList<SongItem>> GetSimilarSongsAsync(
            long songId,
            int limit = 20,
            CancellationToken cancellationToken = default)
        {
            var payload = new[]
            {
                new KeyValuePair<string, string>("songid", songId.ToString()),
                new KeyValuePair<string, string>("limit", limit.ToString()),
                new KeyValuePair<string, string>("offset", "0"),
            };

            try
            {
                using (var response = await _http.EapiPostAsync("/eapi/v1/discovery/similarSong", payload, false, cancellationToken).ConfigureAwait(false))
                {
                    var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                    var songs = ToSongList(JsonValue.Parse(body).GetArray("songs"));
                    if (songs.Count > 0)
                    {
                        return songs;
                    }
                }
            }
            catch (Exception ex) when (!(ex is OperationCanceledException))
            {
                // 主路径失败：走 weapi 回退。
            }

            try
            {
                using (var response = await _http.WeapiPostAsync("/api/discovery/simiSong", JsonText.Object(payload), null, cancellationToken).ConfigureAwait(false))
                {
                    var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                    return ToSongList(JsonValue.Parse(body).GetArray("songs"));
                }
            }
            catch (Exception ex) when (!(ex is OperationCanceledException))
            {
                return Array.Empty<SongItem>();
            }
        }

        private async Task<JsonValue> EapiJsonAsync(string path, CancellationToken cancellationToken)
        {
            using (var response = await _http.EapiPostAsync(path, null, false, cancellationToken).ConfigureAwait(false))
            {
                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                var json = JsonValue.Parse(body);

                // 登录失效时每日推荐 / 推荐歌单仍回 200，但返回的是**非个性化**的通用列表；
                // 需登录的接口则回 301。业务码在这一层交出去，会话监视器据此立刻转 Expired。
                _http.ReportBusinessCode(json.GetInt("code", -1));
                return json;
            }
        }

        /// <summary><paramref name="privileges"/> 是响应里与歌曲并列的权益数组，按 id 关联（判定「无版权」要用）。</summary>
        private static IReadOnlyList<SongItem> ToSongList(
            IReadOnlyList<JsonValue>? items,
            IReadOnlyList<JsonValue>? privileges = null)
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

            SongItem.AttachPrivileges(result, privileges);
            return result;
        }
    }
}
