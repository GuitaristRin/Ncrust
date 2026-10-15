using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Account;
using Ncrust.Core.Json;
using Ncrust.Core.Net;
using Ncrust.Core.Platform;
using Ncrust.Core.Util;

namespace Ncrust.Core.Playback
{
    /// <summary>
    /// 一次取链的结果：可播放的 URL + 实际命中档位 + 权益判定。
    ///
    /// 权益字段是这一版新加的：网易对未登录 / 非会员**不报错**，而是给 200 + 45 秒试听 URL、
    /// 或把音质悄悄降档，界面不解释的话用户只会以为「Ncrust 播一半断了 / 音质设置失效」
    /// （见 <c>spec/api/entitlement.md</c>）。
    /// </summary>
    public sealed class ResolvedSongUrl
    {
        public ResolvedSongUrl(
            long songId,
            string? url,
            string requestedLevel,
            string actualLevel,
            string containerType,
            long bitRate,
            PlaybackEntitlement entitlement)
        {
            SongId = songId;
            Url = url;
            RequestedLevel = requestedLevel;
            ActualLevel = actualLevel;
            ContainerType = containerType;
            BitRate = bitRate;
            Entitlement = entitlement;
        }

        public long SongId { get; }

        /// <summary>可播放的 URL；null / 空 = **所有档位**都没取到（调用方必须跳歌，绝不回退 outer/url）。</summary>
        public string? Url { get; }

        public bool HasUrl => !string.IsNullOrEmpty(Url);

        /// <summary>
        /// FLAC 门控之后**实际尝试的第一档**。判定 <c>capped</c> 以它为基准：设备解不了 FLAC
        /// 造成的降级不算账号权限不足（那是设备原因，调用前就已发生）。
        /// </summary>
        public string RequestedLevel { get; }

        /// <summary>服务端实际给的档位（可能低于请求档）。</summary>
        public string ActualLevel { get; }

        /// <summary>实际容器（mp3 / flac / mp4），诊断「有进度没声音」用；取不到 URL 时为空。</summary>
        public string ContainerType { get; }

        public long BitRate { get; }

        /// <summary>播放权益判定（可播 / 试听 / 不可播 + 原因）。</summary>
        public PlaybackEntitlement Entitlement { get; }
    }

    /// <summary>
    /// 歌曲播放 URL 解析（对应 Android <c>SongUrlFetcher.kt</c>）：按
    /// <see cref="QualityLadder"/> 的降级序列逐档请求，返回第一个可播放的 URL；
    /// 全部失败时**仍然返回结果**（<see cref="ResolvedSongUrl.Url"/> 为空 + 判定原因，取自最后一次响应项），
    /// 让上层能说清「为什么跳过」而不是静默跳歌 —— **绝不**回退到
    /// <c>.../song/media/outer/url</c>（它 302 到一个 404 的 HTML，然后一直缓冲）。
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

        /// <param name="requestedLevel">偏好档位（阶梯起点）。</param>
        /// <param name="sessionValid">
        /// 会话是否 <see cref="SessionState.Valid"/>（<c>Unknown</c> 不算）。只影响判定的原因文案
        /// （「需要黑胶 VIP」还是「登录后可播放」），不影响能否播。
        /// </param>
        /// <param name="privilege">歌曲详情的 <c>privilege</c>；拿不到传 null（判定已考虑）。</param>
        public async Task<ResolvedSongUrl> ResolveAsync(
            long songId,
            string requestedLevel,
            bool sessionValid,
            SongPrivilege? privilege = null,
            CancellationToken cancellationToken = default)
        {
            var levels = QualityLadder.FallbackLevels(requestedLevel, _codecProbe.SupportsFlac);
            var firstLevel = levels.Count > 0 ? levels[0] : requestedLevel;

            LevelResponse? lastResponse = null;
            foreach (var level in levels)
            {
                var response = await TryLevelAsync(songId, level, cancellationToken).ConfigureAwait(false);
                if (response == null)
                {
                    continue; // 这一档的请求本身失败（网络 / 解析）：没有响应项可留。
                }

                lastResponse = response;
                if (!response.HasUrl)
                {
                    continue; // code != 200（404 = 无资源 / 未授权）或 url 为空：继续降级。
                }

                return new ResolvedSongUrl(
                    songId,
                    response.Item.Url,
                    firstLevel,
                    response.Item.Level ?? level,
                    response.ContainerType,
                    response.BitRate,
                    PlaybackEntitlement.Classify(firstLevel, sessionValid, response.Item, privilege));
            }

            // 全部档位取不到 URL：用**最后一次**响应项判定原因（无版权 / 需要会员 / 需购买专辑）。
            // 一次响应都没有（全档位网络失败）时 item 为 null → unknown，不编原因。
            return new ResolvedSongUrl(
                songId,
                null,
                firstLevel,
                lastResponse?.Item.Level ?? firstLevel,
                string.Empty,
                0,
                PlaybackEntitlement.Classify(firstLevel, sessionValid, lastResponse?.Item, privilege));
        }

        private async Task<LevelResponse?> TryLevelAsync(long songId, string level, CancellationToken cancellationToken)
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
                return new LevelResponse(
                    PlaybackItem.FromJson(entry),
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

        /// <summary>一档的响应项 + 诊断字段（容器 / 码率）。</summary>
        private sealed class LevelResponse
        {
            public LevelResponse(PlaybackItem item, string containerType, long bitRate)
            {
                Item = item;
                ContainerType = containerType;
                BitRate = bitRate;
            }

            public PlaybackItem Item { get; }

            public string ContainerType { get; }

            public long BitRate { get; }

            /// <summary>code 缺省按 200 处理（老响应不带 code），与既有行为一致。</summary>
            public bool HasUrl => (Item.Code ?? 200) == 200 && !string.IsNullOrEmpty(Item.Url);
        }
    }
}
