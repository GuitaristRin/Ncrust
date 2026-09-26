using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;
using Ncrust.Core.Platform;
using Ncrust.Core.Util;

namespace Ncrust.Core.Playback
{
    /// <summary>取到的可播放 URL 与实际命中档位。</summary>
    public sealed class ResolvedSongUrl
    {
        public ResolvedSongUrl(string url, string actualLevel, string containerType, long bitRate)
        {
            Url = url;
            ActualLevel = actualLevel;
            ContainerType = containerType;
            BitRate = bitRate;
        }

        public string Url { get; }

        /// <summary>服务端实际给的档位（可能低于请求档）。</summary>
        public string ActualLevel { get; }

        /// <summary>实际容器（mp3 / flac / mp4），诊断「有进度没声音」用。</summary>
        public string ContainerType { get; }

        public long BitRate { get; }
    }

    /// <summary>
    /// 歌曲播放 URL 解析（对应 Android <c>SongUrlFetcher.kt</c>）：按
    /// <see cref="QualityLadder"/> 的降级序列逐档请求，返回第一个可播放的 URL；
    /// 全部失败返回 null（**绝不**回退到 <c>.../song/media/outer/url</c>）。
    ///
    /// FLAC 门控由 <see cref="ICodecProbe"/> 决定，Windows 端恒为 true。
    /// </summary>
    public sealed class SongUrlResolver
    {
        private const string SongUrlPath = "/eapi/song/enhance/player/url/v1";

        private readonly NcmHttp _http;
        private readonly ICodecProbe _codecProbe;

        public SongUrlResolver(NcmHttp http, ICodecProbe codecProbe)
        {
            _http = http ?? throw new ArgumentNullException(nameof(http));
            _codecProbe = codecProbe ?? throw new ArgumentNullException(nameof(codecProbe));
        }

        public async Task<ResolvedSongUrl?> ResolveAsync(
            long songId,
            string requestedLevel,
            CancellationToken cancellationToken = default)
        {
            var levels = QualityLadder.FallbackLevels(requestedLevel, _codecProbe.SupportsFlac);
            foreach (var level in levels)
            {
                var result = await TryLevelAsync(songId, level, cancellationToken).ConfigureAwait(false);
                if (result != null)
                {
                    return result;
                }
            }

            return null;
        }

        private async Task<ResolvedSongUrl?> TryLevelAsync(long songId, string level, CancellationToken cancellationToken)
        {
            var payload = new[]
            {
                new KeyValuePair<string, string>("ids", "[" + songId + "]"),
                new KeyValuePair<string, string>("level", level),
                new KeyValuePair<string, string>("header", BuildHeader()),
                // 杜比全景声必须以 mp4(EAC3) 输出，其余音质用 FLAC。
                new KeyValuePair<string, string>("encodeType", level == QualityLadder.Dolby ? "mp4" : "flac"),
            };

            using (var response = await _http
                .EapiPostAsync(SongUrlPath, payload, useInterface: true, cancellationToken)
                .ConfigureAwait(false))
            {
                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                JsonValue json;
                try
                {
                    json = JsonValue.Parse(body);
                }
                catch (JsonParseException)
                {
                    return null;
                }

                var data = json.GetArray("data");
                if (data == null || data.Count == 0)
                {
                    return null;
                }

                var entry = data[0];
                // code != 200 表示该档不可用（404 = 无资源 / 未授权），继续降级。
                if (entry.GetInt("code", 200) != 200)
                {
                    return null;
                }

                var url = entry.GetString("url");
                if (string.IsNullOrEmpty(url))
                {
                    return null;
                }

                return new ResolvedSongUrl(
                    url!,
                    entry.GetString("level", level) ?? level,
                    entry.GetString("type", string.Empty) ?? string.Empty,
                    entry.GetLong("br"));
            }
        }

        private static string BuildHeader() => JsonText.Object(new[]
        {
            new KeyValuePair<string, string>("os", "pc"),
            new KeyValuePair<string, string>("appver", string.Empty),
            new KeyValuePair<string, string>("osver", string.Empty),
            new KeyValuePair<string, string>("deviceId", "pyncm!"),
            new KeyValuePair<string, string>("requestId", RandomText.Next(20_000_000, 30_000_000).ToString()),
        });
    }
}
