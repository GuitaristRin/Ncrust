using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;

namespace Ncrust.Core.Api
{
    /// <summary>
    /// 歌单相关端点（对应 Android <c>PlaylistApi</c> 的歌单详情路径）。
    /// </summary>
    public sealed class PlaylistApi
    {
        private const string PlaylistDetailPath = "/eapi/v6/playlist/detail";
        private const int BatchSize = 500;

        private readonly NcmHttp _http;
        private readonly SongApi _songApi;

        public PlaylistApi(NcmHttp http)
        {
            _http = http ?? throw new ArgumentNullException(nameof(http));
            _songApi = new SongApi(http);
        }

        /// <summary>
        /// 只取歌单的有序单曲 id 列表（红心歌单的分页底表）。业务码非 200 抛
        /// <see cref="NcmApiException"/>。
        /// </summary>
        public async Task<IReadOnlyList<long>> GetPlaylistTrackIdsAsync(
            long playlistId,
            CancellationToken cancellationToken = default)
        {
            using (var response = await _http.EapiPostAsync(PlaylistDetailPath, new[]
            {
                new KeyValuePair<string, string>("id", playlistId.ToString()),
                new KeyValuePair<string, string>("n", "1000"),
                new KeyValuePair<string, string>("s", "0"),
            }, useInterface: false, cancellationToken).ConfigureAwait(false))
            {
                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                var json = JsonValue.Parse(body);
                var code = json.GetInt("code", -1);
                if (code != 200)
                {
                    throw new NcmApiException($"playlist trackIds code={code}");
                }

                var trackIds = json.GetObject("playlist")?.GetArray("trackIds");
                if (trackIds == null)
                {
                    return Array.Empty<long>();
                }

                var ids = new List<long>(trackIds.Count);
                foreach (var entry in trackIds)
                {
                    ids.Add(entry.GetLong("id"));
                }

                return ids;
            }
        }

        /// <summary>
        /// 歌单详情：<c>trackIds</c> 是全量顺序，<c>tracks</c> 只是一部分（约前 20 首）。
        /// 取全量顺序后，用批量 song/detail 补齐缺失项，最后按 <c>trackIds</c> 顺序返回。
        /// 结构与业务码异常抛 <see cref="NcmApiException"/>。
        /// </summary>
        public async Task<IReadOnlyList<SongItem>> GetPlaylistDetailAsync(
            long playlistId,
            CancellationToken cancellationToken = default)
        {
            using (var response = await _http.EapiPostAsync(PlaylistDetailPath, new[]
            {
                new KeyValuePair<string, string>("id", playlistId.ToString()),
                new KeyValuePair<string, string>("n", "1000"),
                new KeyValuePair<string, string>("s", "0"),
            }, useInterface: false, cancellationToken).ConfigureAwait(false))
            {
                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                var json = JsonValue.Parse(body);

                var code = json.GetInt("code", -1);
                if (code != 200)
                {
                    throw new NcmApiException($"playlist detail code={code}");
                }

                var playlist = json.GetObject("playlist");
                if (playlist == null)
                {
                    return Array.Empty<SongItem>();
                }

                var byId = new Dictionary<long, SongItem>();
                var tracks = playlist.GetArray("tracks");
                if (tracks != null)
                {
                    foreach (var track in tracks)
                    {
                        var song = SongItem.FromJson(track);
                        byId[song.Id] = song;
                    }
                }

                var trackIds = playlist.GetArray("trackIds");
                if (trackIds == null)
                {
                    return new List<SongItem>(byId.Values);
                }

                var orderedIds = new List<long>(trackIds.Count);
                var missing = new List<long>();
                foreach (var entry in trackIds)
                {
                    var id = entry.GetLong("id");
                    orderedIds.Add(id);
                    if (!byId.ContainsKey(id))
                    {
                        missing.Add(id);
                    }
                }

                for (var start = 0; start < missing.Count; start += BatchSize)
                {
                    var chunk = missing.GetRange(start, Math.Min(BatchSize, missing.Count - start));
                    var fetched = await _songApi.GetSongDetailAsync(chunk, cancellationToken).ConfigureAwait(false);
                    foreach (var song in fetched)
                    {
                        byId[song.Id] = song;
                    }
                }

                var result = new List<SongItem>(orderedIds.Count);
                foreach (var id in orderedIds)
                {
                    if (byId.TryGetValue(id, out var song))
                    {
                        result.Add(song);
                    }
                }

                return result;
            }
        }
    }
}
