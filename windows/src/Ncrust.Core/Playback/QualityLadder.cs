using System;
using System.Collections.Generic;

namespace Ncrust.Core.Playback
{
    /// <summary>
    /// 音质阶梯（对应 Android <c>SongUrlFetcher</c> 的 fallbackLevels 与
    /// <c>PlayerViewModel</c> 的 qualityRetryLadder）。取链降级顺序与出错重试顺序不同，
    /// 由 <c>spec/fixtures/quality</c> 覆盖。
    /// </summary>
    public static class QualityLadder
    {
        public const string Standard = "standard";
        public const string Higher = "higher";
        public const string ExHigh = "exhigh";
        public const string Lossless = "lossless";
        public const string HiRes = "hires";
        public const string JyEffect = "jyeffect";
        public const string Dolby = "dolby";

        /// <summary>偏好索引顺序；索引即持久化值。</summary>
        public static readonly IReadOnlyList<string> ApiLevels = new[]
        {
            Standard, Higher, ExHigh, Lossless, HiRes, JyEffect, Dolby,
        };

        /// <summary>播放解码失败时的降档顺序（与取链降级序列不同）。</summary>
        private static readonly IReadOnlyList<string> RetryOrder = new[]
        {
            Dolby, JyEffect, HiRes, Lossless, ExHigh, Higher, Standard,
        };

        private static readonly HashSet<string> FlacTiers = new HashSet<string>(StringComparer.Ordinal)
        {
            Lossless, HiRes, JyEffect,
        };

        /// <summary>偏好索引 → 档位；越界（含负数）兜底为 lossless。</summary>
        public static string LevelForPreferenceIndex(int index) =>
            index >= 0 && index < ApiLevels.Count ? ApiLevels[index] : Lossless;

        /// <summary>从请求档位出发、依次尝试的档位序列（假定设备能解 FLAC）。</summary>
        public static IReadOnlyList<string> FallbackLevels(string level) => FallbackLevels(level, true);

        /// <summary>
        /// 同上，但 <paramref name="supportsFlac"/> 为 false 时剔除 lossless / hires /
        /// jyeffect（设备拿到 flac 流只会听到静音，直接跳过）。
        /// </summary>
        public static IReadOnlyList<string> FallbackLevels(string level, bool supportsFlac)
        {
            var levels = BaseFallback(level);
            if (supportsFlac)
            {
                return levels;
            }

            var filtered = new List<string>(levels.Count);
            foreach (var candidate in levels)
            {
                if (!FlacTiers.Contains(candidate))
                {
                    filtered.Add(candidate);
                }
            }

            return filtered;
        }

        public static bool IsFlacTier(string level) => FlacTiers.Contains(level);

        /// <summary>
        /// 出错时降一档。已是 standard 返回 null（上层跳歌）；阶梯外档位从 lossless 起降。
        /// </summary>
        public static string? NextRetryLevel(string level)
        {
            var index = IndexOf(RetryOrder, level);
            if (index >= 0 && index < RetryOrder.Count - 1)
            {
                return RetryOrder[index + 1];
            }

            return index < 0 ? Lossless : (string?)null;
        }

        private static IReadOnlyList<string> BaseFallback(string level) => level switch
        {
            Dolby => new[] { Dolby, HiRes, Lossless, ExHigh, Higher, Standard },
            JyEffect => new[] { JyEffect, Lossless, ExHigh, Higher, Standard },
            HiRes => new[] { HiRes, Lossless, ExHigh, Higher, Standard },
            Lossless => new[] { Lossless, ExHigh, Higher, Standard },
            ExHigh => new[] { ExHigh, Higher, Standard },
            Higher => new[] { Higher, Standard },
            Standard => new[] { Standard },
            _ => new[] { level, Lossless, ExHigh, Higher, Standard },
        };

        private static int IndexOf(IReadOnlyList<string> list, string value)
        {
            for (var i = 0; i < list.Count; i++)
            {
                if (string.Equals(list[i], value, StringComparison.Ordinal))
                {
                    return i;
                }
            }

            return -1;
        }
    }
}
