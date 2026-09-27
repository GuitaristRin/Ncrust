using System;
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
    /// <summary>专辑详情：页头（歌手可点）+ 曲目列表 + 收藏整张专辑。导航参数是专辑 id（long）。</summary>
    public sealed partial class AlbumPage : Page
    {
        private readonly Button _saveButton;
        private long _albumId;
        private AlbumDetailResult _data;

        public AlbumPage()
        {
            InitializeComponent();
            SongActions.AttachContextMenu(SongList);

            _saveButton = new Button { Visibility = Visibility.Collapsed };
            _saveButton.Click += (_, __) => _ = ToggleSavedAsync();
            Header.AddAction(_saveButton);
            Header.SubtitleInvoked += () =>
            {
                var artistId = _data?.Album?.Artist?.Id;
                if (artistId.HasValue)
                {
                    AppShell.ToArtist(artistId.Value);
                }
            };
        }

        protected override void OnNavigatedTo(NavigationEventArgs e)
        {
            base.OnNavigatedTo(e);
            _albumId = e.Parameter is long id ? id : 0;

            var cached = AppServices.Cache.GetAlbum(_albumId);
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
                var fresh = await AppServices.Albums.GetAlbumDetailAsync(_albumId);
                if (fresh.Album != null || fresh.Songs.Count > 0)
                {
                    AppServices.Cache.PutAlbum(_albumId, fresh);
                    Show(fresh);
                }
                else if (_data == null)
                {
                    ShowError("没有找到这张专辑");
                }
            }
            catch (Exception ex)
            {
                // 有缓存时刷新失败不打扰；没有内容才显示错误。
                if (_data == null)
                {
                    ShowError("加载失败：" + ex.Message);
                }
            }
            finally
            {
                LoadingRing.Visibility = Visibility.Collapsed;
            }
        }

        private void Show(AlbumDetailResult data)
        {
            _data = data;
            var album = data.Album;
            var info = DisplayFormat.Join(
                album == null ? null : DisplayFormat.Date(album.PublishTime),
                album?.Company,
                DisplayFormat.SongCount(data.Songs.Count));
            var cover = album?.PicUrl;
            if (string.IsNullOrEmpty(cover))
            {
                cover = data.Songs.FirstOrDefault()?.CoverUrl;
            }

            Header.SetContent("专辑", album?.Name, album?.Artist?.Name, album?.Artist?.Id != null, info, cover);
            Header.SetSongs(data.Songs);
            SongList.ItemsSource = data.Songs;
            UpdateSaveButton();
        }

        private void ShowError(string message)
        {
            ErrorText.Text = message;
            ErrorPanel.Visibility = Visibility.Visible;
        }

        private bool IsSaved() => AppServices.Library.GetSavedAlbums().Any(album => album.AlbumId == _albumId);

        private void UpdateSaveButton()
        {
            _saveButton.Visibility = _data?.Songs.Count > 0 ? Visibility.Visible : Visibility.Collapsed;
            _saveButton.Content = IsSaved() ? "取消收藏" : "收藏整张专辑";
        }

        /// <summary>收藏 / 取消收藏整张专辑（对应 Android 专辑页的收藏按钮）。本地立即生效，云端异步。</summary>
        private async Task ToggleSavedAsync()
        {
            var album = _data?.Album;
            var saving = !IsSaved();
            try
            {
                var task = saving
                    ? AppServices.Library.SubscribeAlbumAsync(new CloudAlbum
                    {
                        AlbumId = _albumId,
                        Name = album?.Name ?? string.Empty,
                        PicUrl = album?.PicUrl ?? string.Empty,
                        Artist = album?.Artist?.Name ?? string.Empty,
                        SongCount = _data?.Songs.Count ?? 0,
                    })
                    : AppServices.Library.RemoveAlbumAsync(_albumId);
                UpdateSaveButton();
                AppShell.Notice(saving ? "已加入库" : "已从库中移除");
                await task;
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }

            UpdateSaveButton();
            SongActions.NotifyLibraryChanged();
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
