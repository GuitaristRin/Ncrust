using System;
using System.Collections.Generic;
using System.Collections.ObjectModel;
using System.Linq;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Login;
using Ncrust.Playback;
using Ncrust.Resources;
using Ncrust.Shell;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Navigation;

namespace Ncrust.Pages
{
    /// <summary>音乐库：收藏单曲 / 收藏专辑 / 我的歌单（对应 Android LibraryScreen）。</summary>
    public sealed partial class LibraryPage : Page
    {
        private const int PlaylistTab = 2;

        // 单曲用可观察集合原地增删：分页追加时不重置滚动位置（直接换 ItemsSource 会跳回顶部）。
        private readonly ObservableCollection<SongItem> _songs = new ObservableCollection<SongItem>();

        private IReadOnlyList<UserPlaylistInfo> _playlists;
        private bool _loadingPlaylists;
        private bool _loadingMore;
        private bool _loadingAllSongs;
        private bool _refreshing;

        public LibraryPage()
        {
            InitializeComponent();
            SongList.ItemsSource = _songs;

            SongActions.AttachContextMenu(SongList, song =>
                AppServices.Library.IsSongPendingSync(song.Id)
                    ? new MenuFlyoutItemBase[] { SongActions.Item("重试同步", Glyphs.Sync, () => _ = RetrySyncAsync()) }
                    : null);
            SongActions.AttachCollectionMenu<CloudAlbum>(
                AlbumGrid,
                album => SongActions.AlbumSongsAsync(album.AlbumId),
                album => new MenuFlyoutItemBase[]
                {
                    new MenuFlyoutSeparator(),
                    SongActions.Item("取消收藏", Glyphs.Delete, () => _ = RemoveAlbumAsync(album)),
                });
            SongActions.AttachCollectionMenu<UserPlaylistInfo>(PlaylistGrid, playlist => SongActions.PlaylistSongsAsync(playlist.Id));

            // 页面被缓存：收藏变化、登录状态变化都要主动刷新。
            SongActions.LibraryChanged += ReloadLocal;
            AppServices.SessionChanged += () =>
            {
                _playlists = null;
                ReloadLocal();
                if (Tabs.SelectedIndex == PlaylistTab)
                {
                    _ = LoadPlaylistsAsync();
                }
            };
        }

        protected override async void OnNavigatedTo(NavigationEventArgs e)
        {
            base.OnNavigatedTo(e);
            ReloadLocal();

            // 本地先渲染；进页时后台拉一次云端（对应 Android LaunchedEffect(Unit) refreshFromCloud）。
            await RefreshFromCloudAsync();
        }

        private async Task RefreshFromCloudAsync()
        {
            if (_refreshing || !AppServices.IsLoggedIn())
            {
                return;
            }

            _refreshing = true;
            try
            {
                await AppServices.Library.RefreshFromCloudAsync();
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }
            finally
            {
                _refreshing = false;
            }

            ReloadLocal();
        }

        // ── 单曲 / 专辑（本地） ─────────────────────────────────────────────

        private void ReloadLocal()
        {
            var library = AppServices.Library;
            Sync(_songs, library.GetSavedSongs());

            var albums = library.GetSavedAlbums();
            AlbumGrid.ItemsSource = albums;
            AlbumEmpty.Visibility = albums.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

            var pending = library.GetPendingSyncSongIds().Count;
            PendingNotice.Visibility = pending > 0 ? Visibility.Visible : Visibility.Collapsed;
            PendingText.Text = pending + " 首仅本地保存，等待同步到云端";

            UpdateSongChrome();
        }

        private void UpdateSongChrome()
        {
            // 空态与数量都以红心歌单的 id 表为准：首屏详情拉取失败时本地可能是空的，
            // 但收藏并不为空，不能因此显示「暂无收藏歌曲」（同 Android 的判据修正）。
            var total = Math.Max(_songs.Count, AppServices.Library.GetLikedSongIds().Count);
            SongEmpty.Visibility = total == 0 ? Visibility.Visible : Visibility.Collapsed;
            SongActionsRow.Visibility = total > 0 ? Visibility.Visible : Visibility.Collapsed;
            SongCountText.Text = DisplayFormat.SongCount(total);
        }

        /// <summary>
        /// 「全部播放 / 插播全部 / 追加全部」的取数：以红心歌单的**全量有序 id** 为准
        /// （issue #30：分页只加载了前几十首详情，只播这一页会漏歌）。
        /// 取数期间禁用三个按钮并显示进度；部分批次失败时照常执行，但如实提示少了几首。
        /// </summary>
        private async Task<IReadOnlyList<SongItem>> LoadAllLikedSongsAsync()
        {
            if (_loadingAllSongs)
            {
                return Array.Empty<SongItem>();
            }

            _loadingAllSongs = true;
            SetSongActionsBusy(true);
            try
            {
                var songs = await AppServices.Library.LoadAllLikedSongsAsync();
                var total = AppServices.Library.GetLikedSongIds().Count;
                if (total > songs.Count)
                {
                    AppShell.Notice("有 " + (total - songs.Count) + " 首未能加载，将按已取到的曲目执行");
                }

                // 刻意不动列表的分页状态（_songs）：它是「已加载的前几页」，
                // 灌进全量会让 loadMore 的切片错位（见 LibraryManager.LoadAllLikedSongsAsync）。
                return songs;
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
                return Array.Empty<SongItem>();
            }
            finally
            {
                _loadingAllSongs = false;
                SetSongActionsBusy(false);
            }
        }

        private void SetSongActionsBusy(bool busy)
        {
            PlayAllSongsButton.IsEnabled = !busy;
            InsertAllSongsButton.IsEnabled = !busy;
            AppendAllSongsButton.IsEnabled = !busy;
            if (busy)
            {
                SongCountText.Text = "正在加载 " + AppServices.Library.GetLikedSongIds().Count + " 首…";
            }
            else
            {
                UpdateSongChrome();
            }
        }

        /// <summary>
        /// 把集合同步成目标列表：目标只是在末尾多了几首（分页 / 云端追加）时原地追加，
        /// 否则整体重排（删除、置顶新收藏）。
        /// </summary>
        private static void Sync(ObservableCollection<SongItem> target, IReadOnlyList<SongItem> source)
        {
            var isPrefix = source.Count >= target.Count;
            for (var i = 0; isPrefix && i < target.Count; i++)
            {
                isPrefix = target[i].Id == source[i].Id;
            }

            if (!isPrefix)
            {
                target.Clear();
            }

            for (var i = target.Count; i < source.Count; i++)
            {
                target.Add(source[i]);
            }
        }

        /// <summary>收藏单曲分页：滚到倒数第 5 首附近时拉下一批（对应 Android 的 snapshotFlow 懒加载）。</summary>
        private async void SongContainerContentChanging(ListViewBase sender, ContainerContentChangingEventArgs args)
        {
            if (_loadingMore || args.ItemIndex < _songs.Count - 5)
            {
                return;
            }

            var total = AppServices.Library.GetLikedSongIds().Count;
            if (_songs.Count >= total)
            {
                return;
            }

            _loadingMore = true;
            try
            {
                var more = await AppServices.Library.LoadMoreLikedSongsAsync();
                if (more.Count > 0)
                {
                    Sync(_songs, AppServices.Library.GetSavedSongs());
                    UpdateSongChrome();
                }
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }
            finally
            {
                _loadingMore = false;
            }
        }

        private async Task RetrySyncAsync()
        {
            try
            {
                await AppServices.Library.RetryPendingSyncAsync();
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }

            ReloadLocal();
        }

        private async Task RemoveAlbumAsync(CloudAlbum album)
        {
            try
            {
                var task = AppServices.Library.RemoveAlbumAsync(album.AlbumId);
                ReloadLocal();
                AppShell.Notice("已从库中移除");
                await task;
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }

            ReloadLocal();
        }

        // ── 歌单（云端，切到这一页才请求） ───────────────────────────────────

        private void TabSelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            if (PlaylistGrid != null && Tabs.SelectedIndex == PlaylistTab && _playlists == null)
            {
                _ = LoadPlaylistsAsync();
            }
        }

        private async Task LoadPlaylistsAsync()
        {
            if (_loadingPlaylists)
            {
                return;
            }

            if (!AppServices.IsLoggedIn())
            {
                PlaylistGrid.ItemsSource = null;
                ShowPlaylistMessage("登录后可以看到你创建和收藏的歌单。", "登录");
                return;
            }

            _loadingPlaylists = true;
            PlaylistMessage.Visibility = Visibility.Collapsed;
            PlaylistRing.Visibility = _playlists == null ? Visibility.Visible : Visibility.Collapsed;
            try
            {
                var uid = await AppServices.Account.GetCurrentUserIdAsync();
                _playlists = await AppServices.Account.GetUserPlaylistsAsync(uid);
                PlaylistGrid.ItemsSource = _playlists;
                if (_playlists.Count == 0)
                {
                    ShowPlaylistMessage("暂无歌单", null);
                }
            }
            catch (Exception ex)
            {
                ShowPlaylistMessage("加载失败：" + ex.Message, "重试");
            }
            finally
            {
                PlaylistRing.Visibility = Visibility.Collapsed;
                _loadingPlaylists = false;
            }
        }

        private void ShowPlaylistMessage(string text, string action)
        {
            PlaylistMessageText.Text = text;
            PlaylistActionButton.Content = action;
            PlaylistActionButton.Visibility = action == null ? Visibility.Collapsed : Visibility.Visible;
            PlaylistMessage.Visibility = Visibility.Visible;
        }

        private void PlaylistActionClick(object sender, RoutedEventArgs e)
        {
            if (AppServices.IsLoggedIn())
            {
                _ = LoadPlaylistsAsync();
            }
            else
            {
                LoginLauncher.Request();
            }
        }

        // ── 点击 ──────────────────────────────────────────────────────────────

        private void SongItemClick(object sender, ItemClickEventArgs e)
        {
            if (e.ClickedItem is SongItem song)
            {
                PlaybackHost.PlaySong(song);
            }
        }

        private async void PlayAllSongsClick(object sender, RoutedEventArgs e)
        {
            var songs = await LoadAllLikedSongsAsync();
            if (songs.Count > 0)
            {
                PlaybackHost.PlayAll(songs);
            }
        }

        private async void InsertAllSongsClick(object sender, RoutedEventArgs e)
        {
            var songs = await LoadAllLikedSongsAsync();
            if (songs.Count > 0)
            {
                PlaybackHost.InsertAllNext(songs);
                AppShell.Notice("已插播 " + songs.Count + " 首");
            }
        }

        private async void AppendAllSongsClick(object sender, RoutedEventArgs e)
        {
            var songs = await LoadAllLikedSongsAsync();
            if (songs.Count > 0)
            {
                PlaybackHost.AppendAll(songs);
                AppShell.Notice("已加入播放队列 " + songs.Count + " 首");
            }
        }

        private void AlbumItemClick(object sender, ItemClickEventArgs e)
        {
            if (e.ClickedItem is CloudAlbum album)
            {
                AppShell.ToAlbum(album.AlbumId);
            }
        }

        private void PlaylistItemClick(object sender, ItemClickEventArgs e)
        {
            if (e.ClickedItem is UserPlaylistInfo playlist)
            {
                AppShell.ToPlaylist(new PlaylistCard
                {
                    Id = playlist.Id,
                    Name = playlist.Name,
                    CoverUrl = playlist.CoverImgUrl,
                    TrackCount = playlist.TrackCount,
                });
            }
        }

        private void RetrySyncClick(object sender, RoutedEventArgs e) => _ = RetrySyncAsync();
    }
}
