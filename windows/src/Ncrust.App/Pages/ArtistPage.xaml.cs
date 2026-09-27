using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Playback;
using Ncrust.Resources;
using Ncrust.Shell;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Navigation;

namespace Ncrust.Pages
{
    /// <summary>歌手详情：专辑墙 + 热门单曲。导航参数是歌手 id（long）。</summary>
    public sealed partial class ArtistPage : Page
    {
        private long _artistId;
        private ArtistAlbumsResult _data;
        private IReadOnlyList<SongItem> _songs;

        public ArtistPage()
        {
            InitializeComponent();
            SongActions.AttachContextMenu(SongList);
            SongActions.AttachCollectionMenu<ArtistAlbumItem>(AlbumGrid, album => SongActions.AlbumSongsAsync(album.Id));
            Tabs.SelectionChanged += (_, __) => UpdateEmpty();
        }

        protected override void OnNavigatedTo(NavigationEventArgs e)
        {
            base.OnNavigatedTo(e);
            _artistId = e.Parameter is long id ? id : 0;

            var cached = AppServices.Cache.GetArtistAlbums(_artistId);
            if (cached != null)
            {
                Show(cached);
            }

            _ = LoadAsync(showLoader: cached == null);
        }

        private async Task LoadAsync(bool showLoader)
        {
            ErrorPanel.Visibility = Visibility.Collapsed;
            LoadingRing.Visibility = showLoader ? Visibility.Visible : Visibility.Collapsed;
            try
            {
                var fresh = await AppServices.Artists.GetArtistAlbumsAsync(_artistId);
                AppServices.Cache.PutArtistAlbums(_artistId, fresh);
                Show(fresh);
            }
            catch (Exception ex)
            {
                if (_data == null)
                {
                    ErrorText.Text = "加载失败：" + ex.Message;
                    ErrorPanel.Visibility = Visibility.Visible;
                }
            }
            finally
            {
                LoadingRing.Visibility = Visibility.Collapsed;
            }

            await LoadHotSongsAsync();
        }

        /// <summary>热门单曲：按歌手名搜 30 首，只留歌手列表里有本人的（与 Android 相同）。</summary>
        private async Task LoadHotSongsAsync()
        {
            var name = _data?.Artist?.Name;
            if (string.IsNullOrEmpty(name))
            {
                return;
            }

            try
            {
                var found = await AppServices.Search.SearchSongsAsync(name, 30);
                _songs = found.Where(song => song.Artists.Any(artist => artist.Name == name)).ToList();
            }
            catch
            {
                _songs = _songs ?? Array.Empty<SongItem>();
            }

            SongList.ItemsSource = _songs;
            Header.SetSongs(_songs);
            UpdateEmpty();
        }

        private void Show(ArtistAlbumsResult data)
        {
            _data = data;
            var artist = data.Artist;
            var info = artist == null ? null : DisplayFormat.ArtistCounts(artist.AlbumSize, artist.MusicSize);
            Header.SetContent("歌手", artist?.Name ?? "未知歌手", null, false, info, artist?.PicUrl);
            AlbumGrid.ItemsSource = data.Albums;
            UpdateEmpty();
        }

        private void UpdateEmpty()
        {
            if (EmptyText == null)
            {
                return;
            }

            string text = null;
            if (Tabs.SelectedIndex <= 0 && _data != null && _data.Albums.Count == 0)
            {
                text = "暂无专辑";
            }
            else if (Tabs.SelectedIndex == 1 && _songs != null && _songs.Count == 0)
            {
                text = "暂无单曲";
            }

            EmptyText.Text = text ?? string.Empty;
            EmptyText.Visibility = text == null ? Visibility.Collapsed : Visibility.Visible;
        }

        private void AlbumItemClick(object sender, ItemClickEventArgs e)
        {
            if (e.ClickedItem is ArtistAlbumItem album)
            {
                AppShell.ToAlbum(album.Id);
            }
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
