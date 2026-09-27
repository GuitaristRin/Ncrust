using System.Collections.Generic;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Login;
using Ncrust.Playback;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Navigation;

namespace Ncrust.Pages
{
    /// <summary>
    /// 首页：每日推荐 / 推荐歌单 / 新歌速递。沿用「先读 ContentCache 直接渲染，再后台刷新写回」
    /// 的加载模式（见 windows/AGENTS.md）。每日推荐需要登录；分区为空时整块隐藏。
    /// </summary>
    public sealed partial class HomePage : Page
    {
        private bool _loading;

        public HomePage()
        {
            InitializeComponent();
            SongActions.AttachContextMenu(DailyList);
            SongActions.AttachContextMenu(NewList);
            SongActions.AttachCollectionMenu<PlaylistCard>(PlaylistGrid, playlist => SongActions.PlaylistSongsAsync(playlist.Id));

            // 登录 / 退出后每日推荐的可见性变了：页面是缓存的，要主动刷新。
            AppServices.SessionChanged += () => _ = LoadAsync();
        }

        protected override async void OnNavigatedTo(NavigationEventArgs e)
        {
            base.OnNavigatedTo(e);

            // 页面被缓存：回到首页时缓存仍新鲜（15s 内）就不再请求。
            if (AppServices.Cache.IsHomeFresh() && PlaylistGrid.ItemsSource != null)
            {
                UpdateSections();
                return;
            }

            await LoadAsync();
        }

        private async Task LoadAsync()
        {
            if (_loading)
            {
                return;
            }

            _loading = true;
            var cache = AppServices.Cache;
            ShowDaily(cache.HomeDailySongs);
            ShowPlaylists(cache.HomeRecommendPlaylists);
            ShowNewSongs(cache.HomeNewSongs);
            UpdateSections();

            var hasAnything = cache.HomeDailySongs != null || cache.HomeRecommendPlaylists != null || cache.HomeNewSongs != null;
            LoadingRing.Visibility = hasAnything ? Visibility.Collapsed : Visibility.Visible;

            try
            {
                // 三块互不依赖：各自刷新、各自落地，一块失败不影响另外两块。
                await Task.WhenAll(RefreshDailyAsync(), RefreshPlaylistsAsync(), RefreshNewSongsAsync());
                cache.MarkHomeWarmed();
            }
            finally
            {
                LoadingRing.Visibility = Visibility.Collapsed;
                UpdateSections();
                _loading = false;
            }
        }

        private async Task RefreshDailyAsync()
        {
            if (!AppServices.IsLoggedIn())
            {
                return;
            }

            try
            {
                var daily = await AppServices.Discovery.GetDailyRecommendSongsAsync();
                if (daily.Count > 0)
                {
                    AppServices.Cache.HomeDailySongs = daily;
                    ShowDaily(daily);
                }
            }
            catch
            {
                // 刷新失败保留缓存（或空态），不打断页面。
            }
        }

        private async Task RefreshPlaylistsAsync()
        {
            try
            {
                var playlists = await AppServices.Discovery.GetRecommendPlaylistsAsync();
                if (playlists.Count > 0)
                {
                    AppServices.Cache.HomeRecommendPlaylists = playlists;
                    ShowPlaylists(playlists);
                }
            }
            catch
            {
            }
        }

        private async Task RefreshNewSongsAsync()
        {
            try
            {
                var newSongs = await AppServices.Discovery.GetTopSongsAsync(30, 0);
                if (newSongs.Count > 0)
                {
                    AppServices.Cache.HomeNewSongs = newSongs;
                    ShowNewSongs(newSongs);
                }
            }
            catch
            {
            }
        }

        private void ShowDaily(IReadOnlyList<SongItem> songs)
        {
            if (songs != null)
            {
                DailyList.ItemsSource = songs;
            }
        }

        private void ShowPlaylists(IReadOnlyList<PlaylistCard> playlists)
        {
            if (playlists != null)
            {
                PlaylistGrid.ItemsSource = playlists;
            }
        }

        private void ShowNewSongs(IReadOnlyList<SongItem> songs)
        {
            if (songs != null)
            {
                NewList.ItemsSource = songs;
            }
        }

        /// <summary>分区为空整块隐藏；未登录时显示登录提示（每日推荐需要登录）。</summary>
        private void UpdateSections()
        {
            var loggedIn = AppServices.IsLoggedIn();
            LoginPrompt.Visibility = loggedIn ? Visibility.Collapsed : Visibility.Visible;
            DailySection.Visibility = loggedIn && HasItems(DailyList.ItemsSource) ? Visibility.Visible : Visibility.Collapsed;

            // 私人 FM 需要登录；有昵称时叫「某某的电台」（同 Android fmRadioTitle）。
            FmCard.Visibility = loggedIn ? Visibility.Visible : Visibility.Collapsed;
            var nickname = AppServices.Cache.UserProfile?.Nickname;
            FmTitle.Text = string.IsNullOrEmpty(nickname) ? "私人 FM" : nickname + "的电台";

            PlaylistSection.Visibility = loggedIn || HasItems(PlaylistGrid.ItemsSource) ? Visibility.Visible : Visibility.Collapsed;
            NewSection.Visibility = HasItems(NewList.ItemsSource) ? Visibility.Visible : Visibility.Collapsed;
        }

        private static bool HasItems(object source) =>
            source is System.Collections.ICollection collection && collection.Count > 0;

        private void SongItemClick(object sender, ItemClickEventArgs e)
        {
            // 与 Android HomeScreen 一致：点歌是 playSongItem（插到当前曲之后播放），不替换队列。
            if (e.ClickedItem is SongItem song)
            {
                PlaybackHost.PlaySong(song);
            }
        }

        private async void FmClick(object sender, RoutedEventArgs e)
        {
            FmCard.IsEnabled = false;
            FmSubtitle.Text = "正在调频…";
            try
            {
                if (!await PlaybackHost.StartFmAsync())
                {
                    Shell.AppShell.Notice("私人 FM 暂时不可用，请稍后再试");
                }
            }
            finally
            {
                FmSubtitle.Text = "无限播放";
                FmCard.IsEnabled = true;
            }
        }

        private void PlaylistItemClick(object sender, ItemClickEventArgs e)
        {
            if (e.ClickedItem is PlaylistCard playlist)
            {
                Shell.AppShell.ToPlaylist(playlist);
            }
        }

        /// <summary>手动刷新每日推荐（对应 Android 每日推荐区的刷新键，#26）。</summary>
        private async void RefreshDailyClick(object sender, RoutedEventArgs e)
        {
            RefreshDailyButton.IsEnabled = false;
            try
            {
                await RefreshDailyAsync();
                UpdateSections();
            }
            finally
            {
                RefreshDailyButton.IsEnabled = true;
            }
        }

        private void PlayDailyClick(object sender, RoutedEventArgs e)
        {
            // 「全部播放」才是显式替换队列。
            if (DailyList.ItemsSource is IReadOnlyList<SongItem> songs && songs.Count > 0)
            {
                PlaybackHost.PlayAll(songs);
            }
        }

        private void LoginClick(object sender, RoutedEventArgs e) => LoginLauncher.Request();
    }
}
