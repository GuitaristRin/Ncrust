using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Playback;
using Ncrust.Resources;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Navigation;

namespace Ncrust.Pages
{
    /// <summary>
    /// 歌单详情。导航参数是 <see cref="PlaylistCard"/>（id + 名称 + 封面，页头立即可显示；对应 Android
    /// NavRoutes.playlist 带的参数）。歌单接口本身只取曲目；封面缺失时用第一首歌的专辑封面。
    /// </summary>
    public sealed partial class PlaylistPage : Page
    {
        private PlaylistCard _playlist;
        private IReadOnlyList<SongItem> _songs;

        public PlaylistPage()
        {
            InitializeComponent();
            SongActions.AttachContextMenu(SongList);
        }

        protected override void OnNavigatedTo(NavigationEventArgs e)
        {
            base.OnNavigatedTo(e);
            _playlist = e.Parameter as PlaylistCard ?? new PlaylistCard();

            var cached = AppServices.Cache.GetPlaylistSongs(_playlist.Id);
            Show(cached ?? Array.Empty<SongItem>());
            _ = LoadAsync(showLoader: cached == null);
        }

        private async Task LoadAsync(bool showLoader)
        {
            ErrorPanel.Visibility = Visibility.Collapsed;
            LoadingRing.Visibility = showLoader ? Visibility.Visible : Visibility.Collapsed;
            try
            {
                var fresh = await AppServices.Playlists.GetPlaylistDetailAsync(_playlist.Id);
                AppServices.Cache.PutPlaylistSongs(_playlist.Id, fresh);
                Show(fresh);
            }
            catch (Exception ex)
            {
                if (_songs == null || _songs.Count == 0)
                {
                    ErrorText.Text = "加载失败：" + ex.Message;
                    ErrorPanel.Visibility = Visibility.Visible;
                }
            }
            finally
            {
                LoadingRing.Visibility = Visibility.Collapsed;
            }
        }

        private void Show(IReadOnlyList<SongItem> songs)
        {
            _songs = songs;
            var cover = string.IsNullOrEmpty(_playlist.CoverUrl) ? songs.FirstOrDefault()?.CoverUrl : _playlist.CoverUrl;
            var title = string.IsNullOrEmpty(_playlist.Name) ? "歌单" : _playlist.Name;
            var info = songs.Count > 0 ? DisplayFormat.SongCount(songs.Count) : null;

            Header.SetContent("歌单", title, null, false, info, cover);
            Header.SetSongs(songs);
            SongList.ItemsSource = songs;
        }

        private void SongItemClick(object sender, ItemClickEventArgs e)
        {
            if (e.ClickedItem is SongItem song)
            {
                PlaybackHost.PlaySong(song);
            }
        }

        private void RetryClick(object sender, RoutedEventArgs e) => _ = LoadAsync(showLoader: true);
    }
}
