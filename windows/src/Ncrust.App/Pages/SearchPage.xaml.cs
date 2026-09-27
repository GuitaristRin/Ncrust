using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Playback;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Navigation;

namespace Ncrust.Pages
{
    /// <summary>
    /// 搜索结果：歌曲 / 专辑 / 歌手三类（对应 Android SearchScreen）。查询词由导航参数带入。
    /// 三类并发请求，各自回来就各自渲染；新查询会取消上一次还没回来的请求。
    /// </summary>
    public sealed partial class SearchPage : Page
    {
        private string _query;
        private CancellationTokenSource _cts;

        private IReadOnlyList<SongItem> _songs;
        private IReadOnlyList<AlbumSearchItem> _albums;
        private IReadOnlyList<ArtistSearchItem> _artists;

        public SearchPage()
        {
            InitializeComponent();
            Tabs.SelectedIndex = 0;
            SongActions.AttachContextMenu(SongList);
            SongActions.AttachCollectionMenu<AlbumSearchItem>(AlbumGrid, album => SongActions.AlbumSongsAsync(album.Id));
        }

        protected override void OnNavigatedTo(NavigationEventArgs e)
        {
            base.OnNavigatedTo(e);
            var query = (e.Parameter as string ?? string.Empty).Trim();

            // 返回到缓存的本页（参数为空或未变）时保留原结果。
            if (query.Length == 0 || query == _query)
            {
                return;
            }

            _query = query;
            QueryText.Text = query;
            Tabs.SelectedIndex = 0;
            _ = SearchAsync(query);
        }

        private async Task SearchAsync(string query)
        {
            _cts?.Cancel();
            var cts = new CancellationTokenSource();
            _cts = cts;

            _songs = null;
            _albums = null;
            _artists = null;
            SongList.ItemsSource = null;
            AlbumGrid.ItemsSource = null;
            ArtistList.ItemsSource = null;
            UpdateVisibility();

            var search = AppServices.Search;
            var songsTask = Run(() => search.SearchSongsAsync(query, 50, cts.Token), cts, list =>
            {
                _songs = list;
                SongList.ItemsSource = list;
            });
            var albumsTask = Run(() => search.SearchAlbumsAsync(query, 40, cts.Token), cts, list =>
            {
                _albums = list;
                AlbumGrid.ItemsSource = list;
            });
            var artistsTask = Run(() => search.SearchArtistsAsync(query, 40, cts.Token), cts, list =>
            {
                _artists = list;
                ArtistList.ItemsSource = list;
            });

            await Task.WhenAll(songsTask, albumsTask, artistsTask);
        }

        /// <summary>一类结果：请求 → 未被取消才落到界面 → 刷新空态 / 加载态。失败按空结果处理。</summary>
        private async Task Run<T>(Func<Task<IReadOnlyList<T>>> fetch, CancellationTokenSource cts, Action<IReadOnlyList<T>> apply)
        {
            IReadOnlyList<T> result;
            try
            {
                result = await fetch();
            }
            catch (OperationCanceledException)
            {
                return;
            }
            catch
            {
                result = Array.Empty<T>();
            }

            if (cts.IsCancellationRequested)
            {
                return;
            }

            apply(result);
            UpdateVisibility();
        }

        private void TabSelectionChanged(object sender, SelectionChangedEventArgs e) => UpdateVisibility();

        /// <summary>当前 Tab 那一类还在加载时转圈，加载完为空时显示空态（Tab 切换本身由 Pivot 负责）。</summary>
        private void UpdateVisibility()
        {
            // Pivot 在 InitializeComponent 期间就可能触发 SelectionChanged，此时后面的命名元素还没连上。
            if (LoadingRing == null || EmptyText == null)
            {
                return;
            }

            var tab = Math.Max(0, Tabs.SelectedIndex);

            int? count;
            switch (tab)
            {
                case 1:
                    count = _albums?.Count;
                    break;
                case 2:
                    count = _artists?.Count;
                    break;
                default:
                    count = _songs?.Count;
                    break;
            }

            var searching = _query != null;
            LoadingRing.Visibility = searching && count == null ? Visibility.Visible : Visibility.Collapsed;
            EmptyText.Visibility = count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        private void SongItemClick(object sender, ItemClickEventArgs e)
        {
            if (!(e.ClickedItem is SongItem song))
            {
                return;
            }

            // 与 Android 一致：搜索结果点歌是「插到当前曲之后并播放」，不替换整个队列；并记入搜索历史。
            PlaybackHost.PlaySong(song);
            _ = AppServices.SearchHistory.AddSongAsync(song);
        }

        private void AlbumItemClick(object sender, ItemClickEventArgs e)
        {
            if (e.ClickedItem is AlbumSearchItem album)
            {
                _ = AppServices.SearchHistory.AddAlbumAsync(album);
                Shell.AppShell.ToAlbum(album.Id);
            }
        }

        private void ArtistItemClick(object sender, ItemClickEventArgs e)
        {
            if (e.ClickedItem is ArtistSearchItem artist)
            {
                _ = AppServices.SearchHistory.AddArtistAsync(artist);
                Shell.AppShell.ToArtist(artist.Id);
            }
        }
    }
}
