using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Json;
using Ncrust.Core.Net;
using Ncrust.Core.Platform;

namespace Ncrust.Core.Search
{
    /// <summary>
    /// 搜索历史的分类。歌曲 / 专辑 / 歌手的取值与 Android 的 type 一致；<see cref="Query"/>（搜过的关键词）
    /// 是桌面端新增的：Android 只记点过的条目，搜了没点就什么都不留，桌面上负责人要求保留搜索记录。
    /// </summary>
    public enum SearchHistoryType
    {
        Query = 0,
        Song = 1,
        Album = 10,
        Artist = 100,
    }

    public sealed class HistoryItem
    {
        public long Id { get; set; }

        public string Title { get; set; } = string.Empty;

        public string CoverUrl { get; set; } = string.Empty;

        public string? Subtitle { get; set; }

        public long Timestamp { get; set; }
    }

    /// <summary>
    /// 搜索历史（对应 Android <c>SearchHistoryManager.kt</c>）：每类最多 10 条、14 天过期，
    /// 新条目置顶、按 id 去重。存储走 <see cref="IFileStore"/> 的 <c>search_history.json</c>
    /// （不用 LocalSettings，避免单值大小上限）。
    /// </summary>
    public sealed class SearchHistory
    {
        public const int MaxItems = 10;
        public const long TtlMs = 14L * 24 * 60 * 60 * 1000;

        private const string FileName = "search_history.json";

        private readonly IFileStore _files;
        private readonly Func<long> _clock;
        private readonly SemaphoreSlim _gate = new SemaphoreSlim(1, 1);

        public SearchHistory(IFileStore files, Func<long>? clock = null)
        {
            _files = files ?? throw new ArgumentNullException(nameof(files));
            _clock = clock ?? (() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds());
        }

        public Task AddSongAsync(SongItem song, CancellationToken cancellationToken = default) =>
            AddAsync(SearchHistoryType.Song, song.Id, song.Name, song.Album?.PicUrl ?? string.Empty,
                song.Artists.Count > 0 ? song.Artists[0].Name : null, cancellationToken);

        public Task AddAlbumAsync(AlbumSearchItem album, CancellationToken cancellationToken = default) =>
            AddAsync(SearchHistoryType.Album, album.Id, album.Name, album.PicUrl, album.Artist?.Name, cancellationToken);

        public Task AddArtistAsync(ArtistSearchItem artist, CancellationToken cancellationToken = default) =>
            AddAsync(SearchHistoryType.Artist, artist.Id, artist.Name, artist.PicUrl, null, cancellationToken);

        /// <summary>记一次搜索关键词（首尾空白去掉；忽略大小写去重，新的置顶）。空串忽略。</summary>
        public Task AddQueryAsync(string query, CancellationToken cancellationToken = default)
        {
            var trimmed = (query ?? string.Empty).Trim();
            return trimmed.Length == 0
                ? Task.CompletedTask
                : AddAsync(SearchHistoryType.Query, 0, trimmed, string.Empty, null, cancellationToken);
        }

        public Task<IReadOnlyList<HistoryItem>> GetQueriesAsync(CancellationToken cancellationToken = default) =>
            GetAllAsync(SearchHistoryType.Query, cancellationToken);

        public Task<IReadOnlyList<HistoryItem>> GetSongsAsync(CancellationToken cancellationToken = default) =>
            GetAllAsync(SearchHistoryType.Song, cancellationToken);

        public Task<IReadOnlyList<HistoryItem>> GetAlbumsAsync(CancellationToken cancellationToken = default) =>
            GetAllAsync(SearchHistoryType.Album, cancellationToken);

        public Task<IReadOnlyList<HistoryItem>> GetArtistsAsync(CancellationToken cancellationToken = default) =>
            GetAllAsync(SearchHistoryType.Artist, cancellationToken);

        public Task RemoveQueryAsync(string query, CancellationToken cancellationToken = default) =>
            UpdateAsync(all => all[SearchHistoryType.Query].RemoveAll(item => SameQuery(item.Title, query)) > 0, cancellationToken);

        /// <summary>按 id 删除一条。关键词没有 id（都是 0），要用 <see cref="RemoveQueryAsync"/>。</summary>
        public Task RemoveAsync(SearchHistoryType type, long id, CancellationToken cancellationToken = default)
        {
            if (type == SearchHistoryType.Query)
            {
                throw new ArgumentException("关键词没有 id，请用 RemoveQueryAsync", nameof(type));
            }

            return UpdateAsync(all => all[type].RemoveAll(item => item.Id == id) > 0, cancellationToken);
        }

        public Task ClearSectionAsync(SearchHistoryType type, CancellationToken cancellationToken = default) =>
            UpdateAsync(all =>
            {
                all[type].Clear();
                return true;
            }, cancellationToken);

        /// <summary>清空全部四类（搜索框下拉里的「清除搜索记录」）。</summary>
        public Task ClearAllAsync(CancellationToken cancellationToken = default) =>
            UpdateAsync(all =>
            {
                foreach (var list in all.Values)
                {
                    list.Clear();
                }

                return true;
            }, cancellationToken);

        private static bool SameQuery(string a, string b) =>
            string.Equals((a ?? string.Empty).Trim(), (b ?? string.Empty).Trim(), StringComparison.OrdinalIgnoreCase);

        private Task AddAsync(
            SearchHistoryType type,
            long id,
            string title,
            string coverUrl,
            string? subtitle,
            CancellationToken cancellationToken) =>
            UpdateAsync(all =>
            {
                var list = all[type];
                var now = _clock();
                list.RemoveAll(item => now - item.Timestamp > TtlMs);
                if (type == SearchHistoryType.Query)
                {
                    list.RemoveAll(item => SameQuery(item.Title, title));
                }
                else
                {
                    list.RemoveAll(item => item.Id == id);
                }

                list.Insert(0, new HistoryItem
                {
                    Id = id,
                    Title = title,
                    CoverUrl = coverUrl,
                    Subtitle = subtitle,
                    Timestamp = now,
                });

                if (list.Count > MaxItems)
                {
                    list.RemoveRange(MaxItems, list.Count - MaxItems);
                }

                return true;
            }, cancellationToken);

        private async Task<IReadOnlyList<HistoryItem>> GetAllAsync(SearchHistoryType type, CancellationToken cancellationToken)
        {
            IReadOnlyList<HistoryItem> result = Array.Empty<HistoryItem>();
            await UpdateAsync(all =>
            {
                var list = all[type];
                var now = _clock();
                var removed = list.RemoveAll(item => now - item.Timestamp > TtlMs);
                result = list.ToArray();
                return removed > 0;
            }, cancellationToken).ConfigureAwait(false);
            return result;
        }

        /// <summary>
        /// 所有读改写都串行：整份文件读出来、改、整份写回。并发时（回车记关键词的同时点了一首歌、
        /// 下拉读记录的同时清掉过期项）后写的会覆盖先写的；更糟的是 UWP 写文件是先截断再写，
        /// 另一路恰好在截断后读到空文件，就把全部记录存成空的。
        /// </summary>
        private async Task UpdateAsync(
            Func<Dictionary<SearchHistoryType, List<HistoryItem>>, bool> mutate,
            CancellationToken cancellationToken)
        {
            await _gate.WaitAsync(cancellationToken).ConfigureAwait(false);
            try
            {
                var all = await LoadAsync(cancellationToken).ConfigureAwait(false);
                if (mutate(all))
                {
                    await SaveAsync(all, cancellationToken).ConfigureAwait(false);
                }
            }
            finally
            {
                _gate.Release();
            }
        }

        private async Task<Dictionary<SearchHistoryType, List<HistoryItem>>> LoadAsync(CancellationToken cancellationToken)
        {
            var result = NewEmpty();
            var raw = await _files.ReadTextAsync(FileName).ConfigureAwait(false);
            if (string.IsNullOrEmpty(raw))
            {
                return result;
            }

            JsonValue json;
            try
            {
                json = JsonValue.Parse(raw!);
            }
            catch (JsonParseException)
            {
                return result;
            }

            foreach (var type in AllTypes)
            {
                var array = json.GetArray(KeyFor(type));
                if (array == null)
                {
                    continue;
                }

                foreach (var element in array)
                {
                    result[type].Add(new HistoryItem
                    {
                        Id = element.GetLong("id"),
                        Title = element.GetString("title", string.Empty) ?? string.Empty,
                        CoverUrl = element.GetString("coverUrl", string.Empty) ?? string.Empty,
                        Subtitle = element.GetString("subtitle"),
                        Timestamp = element.GetLong("timestamp"),
                    });
                }
            }

            return result;
        }

        private Task SaveAsync(Dictionary<SearchHistoryType, List<HistoryItem>> all, CancellationToken cancellationToken)
        {
            var sb = new StringBuilder();
            sb.Append('{');
            var first = true;
            foreach (var type in AllTypes)
            {
                if (!first)
                {
                    sb.Append(',');
                }

                first = false;
                sb.Append('"').Append(KeyFor(type)).Append("\":[");
                var list = all[type];
                for (var i = 0; i < list.Count; i++)
                {
                    if (i > 0)
                    {
                        sb.Append(',');
                    }

                    var item = list[i];
                    sb.Append("{\"id\":").Append(item.Id)
                        .Append(",\"title\":").Append(JsonText.Escape(item.Title))
                        .Append(",\"coverUrl\":").Append(JsonText.Escape(item.CoverUrl));
                    if (item.Subtitle != null)
                    {
                        sb.Append(",\"subtitle\":").Append(JsonText.Escape(item.Subtitle));
                    }

                    sb.Append(",\"timestamp\":").Append(item.Timestamp).Append('}');
                }

                sb.Append(']');
            }

            sb.Append('}');
            return _files.WriteTextAsync(FileName, sb.ToString());
        }

        private static readonly SearchHistoryType[] AllTypes =
        {
            SearchHistoryType.Query,
            SearchHistoryType.Song, SearchHistoryType.Album, SearchHistoryType.Artist,
        };

        private static Dictionary<SearchHistoryType, List<HistoryItem>> NewEmpty() =>
            new Dictionary<SearchHistoryType, List<HistoryItem>>
            {
                [SearchHistoryType.Query] = new List<HistoryItem>(),
                [SearchHistoryType.Song] = new List<HistoryItem>(),
                [SearchHistoryType.Album] = new List<HistoryItem>(),
                [SearchHistoryType.Artist] = new List<HistoryItem>(),
            };

        private static string KeyFor(SearchHistoryType type) => type switch
        {
            SearchHistoryType.Query => "queries",
            SearchHistoryType.Song => "songs",
            SearchHistoryType.Album => "albums",
            _ => "artists",
        };
    }
}
