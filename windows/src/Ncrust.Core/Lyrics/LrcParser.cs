using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text.RegularExpressions;

namespace Ncrust.Core.Lyrics
{
    /// <summary>
    /// LRC 时间戳解析（对应 Android <c>LrcParser.kt</c>）。格式 <c>[MM:SS.mm]</c> 或
    /// <c>[MM:SS.mmm]</c>；两位毫秒按十毫秒解释。每行只取第一个时间戳，其余原样进入文本
    /// （与 Android <c>Regex.find</c> 一致，夹具已钉住）。输出按时间升序、稳定。
    /// </summary>
    public static class LrcParser
    {
        private static readonly Regex Timestamp =
            new Regex(@"\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)", RegexOptions.CultureInvariant);

        public static IReadOnlyList<LrcLine> Parse(string? lrcText)
        {
            var lines = new List<LrcLine>();
            if (string.IsNullOrEmpty(lrcText))
            {
                return lines;
            }

            foreach (var raw in lrcText!.Split('\n'))
            {
                var line = raw.Trim();
                var match = Timestamp.Match(line);
                if (!match.Success)
                {
                    continue;
                }

                var minutes = long.Parse(match.Groups[1].Value, CultureInfo.InvariantCulture);
                var seconds = long.Parse(match.Groups[2].Value, CultureInfo.InvariantCulture);
                var millisecondsText = match.Groups[3].Value;
                var milliseconds = long.Parse(millisecondsText, CultureInfo.InvariantCulture);
                if (millisecondsText.Length == 2)
                {
                    milliseconds *= 10;
                }

                var text = match.Groups[4].Value.Trim();
                if (text.Length == 0)
                {
                    continue;
                }

                lines.Add(new LrcLine
                {
                    TimeMs = minutes * 60000 + seconds * 1000 + milliseconds,
                    Text = text,
                });
            }

            // OrderBy 是稳定排序，保持同一时间戳的原有顺序。
            return lines.OrderBy(line => line.TimeMs).ToList();
        }
    }

    /// <summary>双语合并：译文按时间戳精确对齐原句。</summary>
    public static class LyricMerger
    {
        public static IReadOnlyList<LyricLine> Merge(
            IReadOnlyList<LrcLine> original,
            IReadOnlyList<LrcLine>? translation)
        {
            var translationByTime = new Dictionary<long, string>();
            if (translation != null)
            {
                foreach (var line in translation)
                {
                    translationByTime[line.TimeMs] = line.Text;
                }
            }

            var result = new List<LyricLine>(original.Count);
            foreach (var line in original)
            {
                result.Add(new LyricLine
                {
                    TimeMs = line.TimeMs,
                    Text = line.Text,
                    Translation = translationByTime.TryGetValue(line.TimeMs, out var text) ? text : string.Empty,
                });
            }

            return result;
        }
    }
}
