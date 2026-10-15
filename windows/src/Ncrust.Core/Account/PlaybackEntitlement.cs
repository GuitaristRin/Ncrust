using System;
using Ncrust.Core.Json;
using Ncrust.Core.Playback;

namespace Ncrust.Core.Account
{
    /// <summary>
    /// <c>song/enhance/player/url/v1</c> 的 <c>data[i]</c>，只保留判定用得上的字段。
    /// </summary>
    public sealed class PlaybackItem
    {
        public string? Url { get; set; }

        public int? Code { get; set; }

        /// <summary>服务端实际给的档位（可能低于请求档）。</summary>
        public string? Level { get; set; }

        /// <summary><c>4</c> = 数字专辑，<c>1</c> = 仅会员可听，<c>8</c> = 低音质免费。</summary>
        public int? Fee { get; set; }

        /// <summary>非 null 表示这次给的是试听片段（实测 VIP 歌对非会员是 45 秒）。</summary>
        public FreeTrialInfo? FreeTrialInfo { get; set; }

        /// <summary>试听限制的细节。判定不用它（规则只看 url / level / fee / freeTrialInfo 与 privilege.st）。</summary>
        public FreeTrialPrivilege? FreeTrialPrivilege { get; set; }

        public static PlaybackItem FromJson(JsonValue item)
        {
            var trial = item.GetObject("freeTrialInfo");
            var trialPrivilege = item.GetObject("freeTrialPrivilege");
            return new PlaybackItem
            {
                Url = item.GetString("url"),
                Code = JsonNumbers.OptionalInt(item, "code"),
                Level = item.GetString("level"),
                Fee = JsonNumbers.OptionalInt(item, "fee"),
                FreeTrialInfo = trial == null
                    ? null
                    : new FreeTrialInfo(JsonNumbers.OptionalInt(trial, "start"), JsonNumbers.OptionalInt(trial, "end")),
                FreeTrialPrivilege = trialPrivilege == null
                    ? null
                    : new FreeTrialPrivilege(JsonNumbers.OptionalInt(trialPrivilege, "cannotListenReason")),
            };
        }
    }

    public sealed class FreeTrialInfo
    {
        public FreeTrialInfo(int? start, int? end)
        {
            Start = start;
            End = end;
        }

        public int? Start { get; }

        public int? End { get; }

        /// <summary>试听秒数；缺字段时按 0（宁可不提示，也不编一个时长）。</summary>
        public int Seconds => Math.Max(0, (End ?? 0) - (Start ?? 0));
    }

    public sealed class FreeTrialPrivilege
    {
        public FreeTrialPrivilege(int? cannotListenReason) => CannotListenReason = cannotListenReason;

        /// <summary><c>1</c> = 无版权（与 <c>privilege.st &lt; 0</c> 同源，仅作诊断）。</summary>
        public int? CannotListenReason { get; }
    }

    /// <summary>歌曲详情里的 <c>privilege</c>（<c>song/detail</c> / <c>playlist/detail</c> 的 <c>privileges[]</c>）。</summary>
    public sealed class SongPrivilege
    {
        /// <summary><c>st &lt; 0</c> = 无版权 / 已下架。判定 <c>unavailable</c> 原因时唯一可靠的版权信号。</summary>
        public int? St { get; set; }

        /// <summary>账号实际可播的最高档（<c>pl</c>）；单独出现不足以判定，仅作诊断。</summary>
        public long? Pl { get; set; }

        public int? Fee { get; set; }

        public static SongPrivilege FromJson(JsonValue privilege) => new SongPrivilege
        {
            St = JsonNumbers.OptionalInt(privilege, "st"),
            Pl = privilege["pl"] is { IsNumber: true } pl ? pl.LongValue : (long?)null,
            Fee = JsonNumbers.OptionalInt(privilege, "fee"),
        };
    }

    /// <summary>
    /// 单曲播放结果分类（纯函数，三端一致）。规格：<c>spec/api/entitlement.md</c>；
    /// 答案：<c>spec/fixtures/entitlement/playback.json</c>（Windows 侧见 EntitlementFixtureTests）。
    ///
    /// 存在的理由：网易对未登录 / 非会员**不报错**，而是返回 200 + 45 秒试听 URL、或把音质悄悄降到
    /// <c>privilege.pl</c>、或 404 + <c>url: null</c>。客户端照单全收，用户看到的就是
    /// 「放到一半断了 / 音质设置不生效 / 点了没反应」，自然归咎于 Ncrust 本身。
    /// </summary>
    public abstract class PlaybackEntitlement
    {
        private PlaybackEntitlement()
        {
        }

        /// <summary>
        /// 判定一次取链的结果。规则顺序（不可调换，夹具逐条锁住）：
        /// 有 url + <c>freeTrialInfo</c> → 试听；有 url → 完整播放（档位被降则 capped）；
        /// 无 url → 不可播（按 <c>privilege.st</c> / <c>fee</c> 归类原因）。
        /// </summary>
        /// <param name="requestedLevel">
        /// 客户端**实际尝试的第一档**。调用方必须传 FLAC 门控之后的第一档，否则设备解不了 FLAC
        /// 造成的降级会被误判成「账号权限不足」。
        /// </param>
        /// <param name="sessionValid">会话是否 <see cref="SessionState.Valid"/>（<c>Unknown</c> 不算）。只影响原因文案，不影响能否播。</param>
        /// <param name="item">最后一次取链响应项；所有档位都没拿到响应时为 null，只能给 <c>unknown</c>。</param>
        /// <param name="privilege">歌曲详情的 <c>privilege</c>；拿不到就传 null（规则已考虑）。</param>
        public static PlaybackEntitlement Classify(
            string requestedLevel,
            bool sessionValid,
            PlaybackItem? item,
            SongPrivilege? privilege)
        {
            // 一次响应都没有（所有档位网络失败）：没有任何依据区分版权 / 会员，不编原因。
            if (item == null)
            {
                return new Unavailable(EntitlementReason.Unknown);
            }

            // fee 优先取歌曲详情的（更权威），没有详情时退回取链响应项里的。
            var fee = privilege?.Fee ?? item.Fee;

            if (!string.IsNullOrEmpty(item.Url))
            {
                var trial = item.FreeTrialInfo;
                if (trial != null)
                {
                    // 有 url 但带 freeTrialInfo = 官方给的试听片段（实测 VIP 歌对非会员返回 45 秒）。
                    var reason = fee == 4
                        ? EntitlementReason.Album
                        : !sessionValid
                            ? EntitlementReason.Login
                            : EntitlementReason.Vip;
                    return new Trial(trial.Seconds, reason);
                }

                // 只有两端都在 7 级阶梯内才可比；阶梯外档位（如 sky）不参与比较、不算 capped。
                var requestedIndex = LevelIndex(requestedLevel);
                var actualIndex = LevelIndex(item.Level);
                var capped = requestedIndex >= 0 && actualIndex >= 0 && actualIndex < requestedIndex;
                return new Full(
                    capped,
                    capped ? item.Level : null,
                    capped
                        ? (sessionValid ? EntitlementReason.Vip : EntitlementReason.Login)
                        : (EntitlementReason?)null);
            }

            var unavailableReason = (privilege?.St ?? 0) < 0
                ? EntitlementReason.Copyright
                : fee == 4
                    ? EntitlementReason.Album
                    : fee == 1
                        ? (sessionValid ? EntitlementReason.Vip : EntitlementReason.Login)
                        : EntitlementReason.Unknown;
            return new Unavailable(unavailableReason);
        }

        /// <summary>档位在 7 级阶梯里的下标；阶梯外（含 null）返回 -1，表示「不可比较」。</summary>
        internal static int LevelIndex(string? level)
        {
            if (level == null)
            {
                return -1;
            }

            var levels = QualityLadder.ApiLevels;
            for (var i = 0; i < levels.Count; i++)
            {
                if (string.Equals(levels[i], level, StringComparison.Ordinal))
                {
                    return i;
                }
            }

            return -1;
        }

        /// <summary>可完整播放。实际档位低于请求档时 <see cref="Capped"/> 为真（账号权限不足，不是设备原因）。</summary>
        public sealed class Full : PlaybackEntitlement
        {
            public Full(bool capped, string? cappedTo, EntitlementReason? cappedReason)
            {
                Capped = capped;
                CappedTo = cappedTo;
                CappedReason = cappedReason;
            }

            public bool Capped { get; }

            /// <summary>实际拿到的档位（<see cref="Capped"/> 为假时是 null）。</summary>
            public string? CappedTo { get; }

            public EntitlementReason? CappedReason { get; }
        }

        /// <summary>只能播 <see cref="TrialSeconds"/> 秒的试听片段。</summary>
        public sealed class Trial : PlaybackEntitlement
        {
            public Trial(int trialSeconds, EntitlementReason reason)
            {
                TrialSeconds = trialSeconds;
                Reason = reason;
            }

            public int TrialSeconds { get; }

            public EntitlementReason Reason { get; }
        }

        /// <summary>所有档位都拿不到 URL，必须跳歌（绝不回退 <c>song/media/outer/url</c> 兜底）。</summary>
        public sealed class Unavailable : PlaybackEntitlement
        {
            public Unavailable(EntitlementReason reason) => Reason = reason;

            public EntitlementReason Reason { get; }
        }
    }

    /// <summary>JSON 里数字字段可能缺省 / 是 null：缺了要能区分「没有」与「0」（<c>fee == 4</c> 的判定依赖它）。</summary>
    internal static class JsonNumbers
    {
        public static int? OptionalInt(JsonValue value, string key) =>
            value[key] is { IsNumber: true } member ? (int?)member.LongValue : null;
    }
}
