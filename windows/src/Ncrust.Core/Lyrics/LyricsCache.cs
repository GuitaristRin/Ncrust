using System;
using System.Collections.Generic;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;
using Ncrust.Core.Platform;

namespace Ncrust.Core.Lyrics
{
    /// <summary>一条缓存的歌词原文与译文（译文可为空串）。</summary>
    public sealed class CachedLyrics
    {
        public CachedLyrics(string lrc, string translation, long timestamp)
        {
            Lrc = lrc;
            Translation = translation;
            Timestamp = timestamp;
        }

        public string Lrc { get; }

        public string Translation { get; }

        public long Timestamp { get; }
    }

    /// <summary>
    /// 歌词本地缓存（对应 Android <c>LyricsCache.kt</c>）：进程被杀后重进时无需等网络即可
    /// 恢复当前歌歌词。容量上限 200，超出淘汰最旧；内存镜像避免重复读盘。
    /// 只应缓存服务端权威结果（含「确无歌词」的空串），失败不要写。
    /// </summary>
    public sealed class LyricsCache
    {
        public const int MaxEntries = 200;

        private const string FileName = "lyrics_cache.json";

        private readonly IFileStore _files;
        private readonly Func<long> _clock;
        private readonly object _lock = new object();

        private Dictionary<string, CachedLyrics>? _memory;

        public LyricsCache(IFileStore files, Func<long>? clock = null)
        {
            _files = files ?? throw new ArgumentNullException(nameof(files));
            _clock = clock ?? (() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        }

        public async Task<CachedLyrics?> GetAsync(long songId, CancellationToken cancellationToken = default)
        {
            var map = await EnsureLoadedAsync(cancellationToken).ConfigureAwait(false);
            lock (_lock)
            {
                return map.TryGetValue(songId.ToString(), out var value) ? value : null;
            }
        }

        public async Task PutAsync(
            long songId,
            string lrc,
            string translation,
            CancellationToken cancellationToken = default)
        {
            var map = await EnsureLoadedAsync(cancellationToken).ConfigureAwait(false);
            string payload;
            lock (_lock)
            {
                map[songId.ToString()] = new CachedLyrics(lrc, translation, _clock());
                EvictExcess(map);
                payload = Serialize(map);
            }

            await _files.WriteTextAsync(FileName, payload).ConfigureAwait(false);
        }

        private static void EvictExcess(Dictionary<string, CachedLyrics> map)
        {
            if (map.Count <= MaxEntries)
            {
                return;
            }

            var ordered = new List<KeyValuePair<string, CachedLyrics>>(map);
            ordered.Sort((a, b) => a.Value.Timestamp.CompareTo(b.Value.Timestamp));
            var removeCount = map.Count - MaxEntries;
            for (var i = 0; i < removeCount; i++)
            {
                map.Remove(ordered[i].Key);
            }
        }

        private async Task<Dictionary<string, CachedLyrics>> EnsureLoadedAsync(CancellationToken cancellationToken)
        {
            if (_memory != null)
            {
                return _memory;
            }

            var raw = await _files.ReadTextAsync(FileName).ConfigureAwait(false);
            var map = Parse(raw);
            lock (_lock)
            {
                _memory ??= map;
                return _memory;
            }
        }

        private static Dictionary<string, CachedLyrics> Parse(string? raw)
        {
            var map = new Dictionary<string, CachedLyrics>(StringComparer.Ordinal);
            if (string.IsNullOrEmpty(raw))
            {
                return map;
            }

            try
            {
                var json = JsonValue.Parse(raw!);
                if (json.IsObject)
                {
                    foreach (var member in json.Members)
                    {
                        var value = member.Value;
                        map[member.Key] = new CachedLyrics(
                            value.GetString("lrc", string.Empty) ?? string.Empty,
                            value.GetString("tlyric", string.Empty) ?? string.Empty,
                            value.GetLong("timestamp"));
                    }
                }
            }
            catch (JsonParseException)
            {
                // 损坏的缓存直接丢弃，下次重写。
            }

            return map;
        }

        private static string Serialize(Dictionary<string, CachedLyrics> map)
        {
            var sb = new StringBuilder();
            sb.Append('{');
            var first = true;
            foreach (var pair in map)
            {
                if (!first)
                {
                    sb.Append(',');
                }

                first = false;
                sb.Append(JsonText.Escape(pair.Key)).Append(":{")
                    .Append("\"lrc\":").Append(JsonText.Escape(pair.Value.Lrc))
                    .Append(",\"tlyric\":").Append(JsonText.Escape(pair.Value.Translation))
                    .Append(",\"timestamp\":").Append(pair.Value.Timestamp)
                    .Append('}');
            }

            sb.Append('}');
            return sb.ToString();
        }
    }
}
