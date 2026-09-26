using System.Collections.Generic;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Playback;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;

namespace Ncrust.Pages
{
    /// <summary>
    /// 首页：每日推荐 / 推荐歌单 / 新歌速递。沿用「先读 ContentCache 直接渲染，再后台刷新写回」
    /// 的加载模式（见 windows/AGENTS.md）。
    /// </summary>
    public sealed partial class HomePage : Page
    {
        public HomePage()
        {
            InitializeComponent();
            Loaded += OnLoaded;
        }

        private async void OnLoaded(object sender, RoutedEventArgs e) => await LoadAsync();

        private async Task LoadAsync()
        {
            var cache = AppServices.Cache;

            if (cache.HomeDailySongs != null)
            {
                DailyList.ItemsSource = cache.HomeDailySongs;
            }
            else
            {
                LoadingRing.Visibility = Visibility.Visible;
            }

            if (cache.HomeRecommendPlaylists != null)
            {
                PlaylistGrid.ItemsSource = cache.HomeRecommendPlaylists;
            }

            if (cache.HomeNewSongs != null)
            {
                NewList.ItemsSource = cache.HomeNewSongs;
            }

            try
            {
                var daily = await AppServices.Discovery.GetDailyRecommendSongsAsync();
                if (daily.Count > 0)
                {
                    cache.HomeDailySongs = daily;
                    DailyList.ItemsSource = daily;
                }

                var playlists = await AppServices.Discovery.GetRecommendPlaylistsAsync();
                if (playlists.Count > 0)
                {
                    cache.HomeRecommendPlaylists = playlists;
                    PlaylistGrid.ItemsSource = playlists;
                }

                var newSongs = await AppServices.Discovery.GetTopSongsAsync(30, 0);
                if (newSongs.Count > 0)
                {
                    cache.HomeNewSongs = newSongs;
                    NewList.ItemsSource = newSongs;
                }

                cache.MarkHomeWarmed();
            }
            catch
            {
                // 刷新失败保留缓存（或空态），不打断页面。
            }
            finally
            {
                LoadingRing.Visibility = Visibility.Collapsed;
            }
        }

        private void SongItemClick(object sender, ItemClickEventArgs e)
        {
            if (!(e.ClickedItem is SongItem song) || !(sender is ListView list))
            {
                return;
            }

            if (list.ItemsSource is IReadOnlyList<SongItem> songs)
            {
                PlaybackHost.PlayAll(songs, song.Id);
            }
        }
    }
}
