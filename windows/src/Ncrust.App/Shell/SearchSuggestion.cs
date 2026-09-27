using System;
using System.Collections.Generic;
using System.Linq;
using Ncrust.Core.Api;
using Ncrust.Core.Search;
using Ncrust.Pages;
using Windows.UI.Xaml;

namespace Ncrust.Shell
{
    public enum SearchSuggestionKind
    {
        /// <summary>输入时的即时建议：一首歌，选中即播放。</summary>
        Song,

        /// <summary>搜过的关键词：选中重新搜索。</summary>
        Query,

        HistorySong,
        HistoryAlbum,
        HistoryArtist,

        /// <summary>「清除搜索记录」。</summary>
        ClearHistory,
    }

    /// <summary>
    /// 面板搜索框下拉里的一项。输入时是歌曲即时建议；框里为空时是搜索记录
    /// （搜过的关键词 + 从搜索里点过的歌曲 / 专辑 / 歌手，对应 Android 搜索页的历史区）。
    /// 有封面的项左侧显示 40 的小封面，没有的显示图标。
    /// </summary>
    public sealed class SearchSuggestion
    {
        public SearchSuggestionKind Kind { get; set; }

        public long Id { get; set; }

        public string Title { get; set; } = string.Empty;

        public string Subtitle { get; set; } = string.Empty;

        public string CoverUrl { get; set; } = string.Empty;

        public string Glyph { get; set; } = string.Empty;

        internal SongItem Song { get; set; }

        public Visibility CoverVisibility => string.IsNullOrEmpty(CoverUrl) && Kind != SearchSuggestionKind.Song && Kind != SearchSuggestionKind.HistorySong
            ? Visibility.Collapsed
            : Visibility.Visible;

        public Visibility GlyphVisibility => CoverVisibility == Visibility.Visible ? Visibility.Collapsed : Visibility.Visible;

        public Visibility SubtitleVisibility => string.IsNullOrEmpty(Subtitle) ? Visibility.Collapsed : Visibility.Visible;

        /// <summary>搜索记录项行尾的删除键（即时建议与「清除」没有）。</summary>
        public Visibility DeleteVisibility =>
            Kind == SearchSuggestionKind.Song || Kind == SearchSuggestionKind.ClearHistory ? Visibility.Collapsed : Visibility.Visible;

        /// <summary>行尾删除键被点：外壳删掉这条记录并刷新下拉。</summary>
        internal static event Action<SearchSuggestion> RemoveRequested;

        internal static void RequestRemove(SearchSuggestion suggestion) => RemoveRequested?.Invoke(suggestion);

        internal static SearchSuggestion FromSong(SongItem song) => new SearchSuggestion
        {
            Kind = SearchSuggestionKind.Song,
            Id = song.Id,
            Title = song.Name,
            Subtitle = song.ArtistText,
            CoverUrl = song.CoverUrl,
            Song = song,
        };

        /// <summary>
        /// 搜索记录：关键词在前（最多 6 条），然后是点过的歌曲 / 专辑 / 歌手按时间混排（最多 6 条），
        /// 最后一项「清除搜索记录」。都没有时返回空列表。
        /// </summary>
        internal static IReadOnlyList<SearchSuggestion> FromHistory(
            IReadOnlyList<HistoryItem> queries,
            IReadOnlyList<HistoryItem> songs,
            IReadOnlyList<HistoryItem> albums,
            IReadOnlyList<HistoryItem> artists)
        {
            var result = new List<SearchSuggestion>();
            result.AddRange(queries.Take(6).Select(query => new SearchSuggestion
            {
                Kind = SearchSuggestionKind.Query,
                Title = query.Title,
                Glyph = Glyphs.History,
            }));

            var items = songs.Select(item => (item, kind: SearchSuggestionKind.HistorySong))
                .Concat(albums.Select(item => (item, kind: SearchSuggestionKind.HistoryAlbum)))
                .Concat(artists.Select(item => (item, kind: SearchSuggestionKind.HistoryArtist)))
                .OrderByDescending(entry => entry.item.Timestamp)
                .Take(6);
            foreach (var (item, kind) in items)
            {
                result.Add(new SearchSuggestion
                {
                    Kind = kind,
                    Id = item.Id,
                    Title = item.Title,
                    Subtitle = Describe(kind, item.Subtitle),
                    CoverUrl = item.CoverUrl,
                    Glyph = kind == SearchSuggestionKind.HistoryArtist ? Glyphs.Artist : Glyphs.Album,
                    Song = kind == SearchSuggestionKind.HistorySong ? ToSong(item) : null,
                });
            }

            if (result.Count > 0)
            {
                result.Add(new SearchSuggestion
                {
                    Kind = SearchSuggestionKind.ClearHistory,
                    Title = "清除搜索记录",
                    Glyph = Glyphs.Clear,
                });
            }

            return result;
        }

        private static string Describe(SearchSuggestionKind kind, string subtitle)
        {
            var label = kind == SearchSuggestionKind.HistorySong ? "歌曲" : kind == SearchSuggestionKind.HistoryAlbum ? "专辑" : "歌手";
            return string.IsNullOrEmpty(subtitle) ? label : label + " · " + subtitle;
        }

        /// <summary>历史里只存了 id / 标题 / 封面 / 歌手名：点播只需要 id，其余用于显示与 SMTC。</summary>
        private static SongItem ToSong(HistoryItem item) => new SongItem
        {
            Id = item.Id,
            Name = item.Title,
            Artists = string.IsNullOrEmpty(item.Subtitle)
                ? Array.Empty<ArtistRef>()
                : new[] { new ArtistRef { Name = item.Subtitle } },
            Album = new AlbumRef { PicUrl = item.CoverUrl },
        };
    }
}
