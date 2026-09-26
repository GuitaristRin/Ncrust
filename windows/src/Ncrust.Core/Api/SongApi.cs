using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;

namespace Ncrust.Core.Api
{
    /// <summary>歌曲详情与歌词（对应 Android <c>NcmApi.getSongDetail / getLyric</c>）。</summary>
    public sealed class SongApi
    {
        private readonly NcmHttp _http;

        public SongApi(NcmHttp http)
        {
            _http = http ?? throw new ArgumentNullException(nameof(http));
        }

        /// <summary>批量歌曲详情 <c>/api/v3/song/detail</c>，参数 <c>c=[{"id":N},…]</c>。</summary>
        public async Task<IReadOnlyList<SongItem>> GetSongDetailAsync(
            IReadOnlyList<long> songIds,
            CancellationToken cancellationToken = default)
        {
            if (songIds.Count == 0)
            {
                return Array.Empty<SongItem>();
            }

            var sb = new StringBuilder("[");
            for (var i = 0; i < songIds.Count; i++)
            {
                if (i > 0)
                {
                    sb.Append(',');
                }

                sb.Append("{\"id\":").Append(songIds[i]).Append('}');
            }

            sb.Append(']');

            using (var response = await _http.PostFormAsync("/api/v3/song/detail", new[]
            {
                new KeyValuePair<string, string>("c", sb.ToString()),
            }, cancellationToken).ConfigureAwait(false))
            {
                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                var songs = JsonValue.Parse(body).GetArray("songs");
                if (songs == null)
                {
                    return Array.Empty<SongItem>();
                }

                return songs.Select(SongItem.FromJson).ToList();
            }
        }

        /// <summary>歌词 <c>/api/song/lyric</c>，返回原文与翻译（可能为空串）。</summary>
        public async Task<LyricsResult> GetLyricAsync(long songId, CancellationToken cancellationToken = default)
        {
            using (var response = await _http.PostFormAsync("/api/song/lyric", new[]
            {
                new KeyValuePair<string, string>("id", songId.ToString()),
                new KeyValuePair<string, string>("cp", "false"),
                new KeyValuePair<string, string>("tv", "-1"),
                new KeyValuePair<string, string>("lv", "-1"),
                new KeyValuePair<string, string>("rv", "0"),
                new KeyValuePair<string, string>("kv", "0"),
                new KeyValuePair<string, string>("yv", "0"),
                new KeyValuePair<string, string>("ytv", "0"),
                new KeyValuePair<string, string>("yrv", "0"),
            }, cancellationToken).ConfigureAwait(false))
            {
                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                var json = JsonValue.Parse(body);
                var lrc = json.GetObject("lrc")?.GetString("lyric", string.Empty) ?? string.Empty;
                var translation = json.GetObject("tlyric")?.GetString("lyric", string.Empty) ?? string.Empty;
                return new LyricsResult(lrc, translation);
            }
        }
    }
}
