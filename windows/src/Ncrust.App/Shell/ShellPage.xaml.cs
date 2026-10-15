using System;
using System.Collections.Generic;
using System.Linq;
using Ncrust.Login;
using Ncrust.Pages;
using Ncrust.Playback;
using Windows.ApplicationModel.Core;
using Windows.Foundation;
using Windows.System;
using Windows.UI;
using Windows.UI.Core;
using Windows.UI.ViewManagement;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Controls.Primitives;
using Windows.UI.Xaml.Input;
using Windows.UI.Xaml.Media.Imaging;
using Windows.UI.Xaml.Navigation;
using muxc = Microsoft.UI.Xaml.Controls;

namespace Ncrust.Shell
{
    /// <summary>
    /// 外壳：NavigationView 汉堡菜单 + 自定义标题栏 + 播放器层 + 登录层 + 全局快捷键。
    /// </summary>
    public sealed partial class ShellPage : Page
    {
        private readonly DispatcherTimer _noticeTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };

        private LoginPage _login;
        private int _suggestVersion;
        private bool _engineHooked;

        public ShellPage()
        {
            InitializeComponent();
            SetUpTitleBar();
            RegisterAccelerators();

            // 快捷键不在页面上弹出按键提示。
            KeyboardAcceleratorPlacementMode = KeyboardAcceleratorPlacementMode.Hidden;

            AppShell.NavigateHandler = (page, parameter) => NavigateTo(page, parameter);
            AppShell.NoticeHandler = ShowNotice;
            _noticeTimer.Tick += (_, __) => HideNotice();

            SearchSuggestion.RemoveRequested += RemoveHistoryAsync;
            LoginLauncher.LaunchRequested += ShowLogin;
            AppServices.SessionChanged += OnSessionChanged;
            Window.Current.CoreWindow.PointerPressed += OnCoreWindowPointerPressed;
            Loaded += OnLoaded;

            Nav.SelectedItem = HomeItem;
            ContentFrame.Navigate(typeof(HomePage));
        }

        private async void OnLoaded(object sender, RoutedEventArgs e)
        {
            UpdateAccountItem();
            UpdateSessionBar();
            await PlaybackHost.RestoreAsync();

            // 播放权益提示（试听 / 音质受限 / 跳过）走这里，界面文案在 Resources.DisplayFormat。
            if (!_engineHooked)
            {
                _engineHooked = true;
                PlaybackHost.Engine.NoticeRaised += OnPlaybackNotice;
            }

            // 收藏库先读本地（菜单里的「加入库 / 移除收藏」要靠它判断），云端刷新放到后台。
            try
            {
                await AppServices.Library.PreloadAsync();
                SongActions.NotifyLibraryChanged();
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }

            await RefreshSessionAsync(force: true);
            _ = RefreshLibraryAsync();
            _ = CheckForUpdatesQuietlyAsync();
        }

        /// <summary>启动静默查更新（命中 6 小时缓存），有新版就轻提示，具体更新在「设置 → 关于」。</summary>
        private static async System.Threading.Tasks.Task CheckForUpdatesQuietlyAsync()
        {
            try
            {
                var info = await Ncrust.Update.UpdateService.CheckAsync(force: false);
                if (info != null)
                {
                    AppShell.Notice("发现新版本 " + info.Version + "，可在「设置 → 关于」更新");
                }
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }
        }

        private static async System.Threading.Tasks.Task RefreshLibraryAsync()
        {
            if (!AppServices.IsLoggedIn())
            {
                return;
            }

            try
            {
                await AppServices.Library.RefreshFromCloudAsync();
                SongActions.NotifyLibraryChanged();
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }
        }

        // ── 轻提示 ────────────────────────────────────────────────────────────

        private void ShowNotice(string text)
        {
            NoticeText.Text = text;
            NoticeHost.Visibility = Visibility.Visible;
            FadeNotice(1);
            _noticeTimer.Stop();
            _noticeTimer.Start();
        }

        private void HideNotice()
        {
            _noticeTimer.Stop();
            FadeNotice(0);
        }

        /// <summary>Opacity 是独立动画（合成线程执行），不违反「禁止依赖动画」。</summary>
        private void FadeNotice(double to)
        {
            var animation = new Windows.UI.Xaml.Media.Animation.DoubleAnimation
            {
                To = to,
                Duration = TimeSpan.FromMilliseconds(to > 0 ? 150 : 250),
            };
            Windows.UI.Xaml.Media.Animation.Storyboard.SetTarget(animation, NoticeHost);
            Windows.UI.Xaml.Media.Animation.Storyboard.SetTargetProperty(animation, "Opacity");
            var storyboard = new Windows.UI.Xaml.Media.Animation.Storyboard();
            storyboard.Children.Add(animation);
            if (to <= 0)
            {
                storyboard.Completed += (_, __) =>
                {
                    if (!_noticeTimer.IsEnabled)
                    {
                        NoticeHost.Visibility = Visibility.Collapsed;
                    }
                };
            }

            storyboard.Begin();
        }

        // ── 标题栏 ────────────────────────────────────────────────────────────

        private void SetUpTitleBar()
        {
            var coreTitleBar = CoreApplication.GetCurrentView().TitleBar;
            coreTitleBar.ExtendViewIntoTitleBar = true;
            coreTitleBar.LayoutMetricsChanged += (bar, _) => ApplyTitleBarHeight(bar.Height);

            // 播放器的真全屏走系统全屏：标题栏隐藏，覆盖层不用再让出标题栏高度。
            ApplicationView.GetForCurrentView().VisibleBoundsChanged += (_, __) =>
                ApplyTitleBarHeight(coreTitleBar.Height > 0 ? coreTitleBar.Height : 32);
            ApplyTitleBarHeight(coreTitleBar.Height > 0 ? coreTitleBar.Height : 32);
            Window.Current.SetTitleBar(AppTitleBar);

            // 系统标题栏按钮（最小化 / 最大化 / 关闭）：透明底，前景随明暗主题（切换主题时 AppTheme 会再调一次）。
            AppTheme.ApplyTitleBarColors();

            // SetPreferredMinSize 的上限是 500x500。
            ApplicationView.GetForCurrentView().SetPreferredMinSize(new Size(360, 500));

            Window.Current.Activated += (_, args) =>
            {
                AppTitle.Opacity = args.WindowActivationState == CoreWindowActivationState.Deactivated ? 0.5 : 1.0;

                // 回到前台时复核一次会话（距上次判定 ≥ 30 分钟才真打网络，间隔由 SessionMonitor 判断）。
                if (args.WindowActivationState != CoreWindowActivationState.Deactivated)
                {
                    _ = RefreshSessionAsync(force: false);
                }
            };
        }

        private void ApplyTitleBarHeight(double height)
        {
            AppTitleBar.Height = height;

            // 系统全屏（播放器真全屏）时标题栏隐藏：自绘的图标与标题一并隐藏，覆盖层不再让出高度。
            var fullScreen = ApplicationView.GetForCurrentView().IsFullScreenMode;
            AppTitleBar.Visibility = fullScreen ? Visibility.Collapsed : Visibility.Visible;
            if (fullScreen)
            {
                height = 0;
            }


            // 标题栏区域的输入被系统拿去拖动窗口：覆盖层让出这一条，否则里面的按钮点不到。
            Player.Margin = new Thickness(0, height, 0, 0);
            OverlayHost.Margin = new Thickness(0, height, 0, 0);
        }

        /// <summary>标题文字给左上角的返回 / 汉堡按钮让位（最小模式两个按钮都在标题栏这一行）。</summary>
        private void NavDisplayModeChanged(muxc.NavigationView sender, muxc.NavigationViewDisplayModeChangedEventArgs args)
        {
            var left = sender.DisplayMode == muxc.NavigationViewDisplayMode.Minimal
                ? sender.CompactPaneLength * 2
                : sender.CompactPaneLength;
            AppTitleBar.Margin = new Thickness(left, 0, 0, 0);
        }

        // ── 导航 ──────────────────────────────────────────────────────────────

        private void NavItemInvoked(muxc.NavigationView sender, muxc.NavigationViewItemInvokedEventArgs args)
        {
            if (args.IsSettingsInvoked)
            {
                NavigateTo(typeof(SettingsPage));
                return;
            }

            var tag = (args.InvokedItemContainer as muxc.NavigationViewItem)?.Tag as string;
            switch (tag)
            {
                case "home":
                    NavigateTo(typeof(HomePage));
                    break;
                case "library":
                    NavigateTo(typeof(LibraryPage));
                    break;
                case "account":
                    // 已登录：账户资料与退出登录在设置页顶部（与 Android「我的」页一致）。
                    if (AppServices.IsLoggedIn())
                    {
                        NavigateTo(typeof(SettingsPage));
                    }
                    else
                    {
                        ShowLogin();
                    }

                    break;
            }
        }

        private void NavigateTo(Type page, object parameter = null)
        {
            if (ContentFrame.CurrentSourcePageType != page || parameter != null)
            {
                ContentFrame.Navigate(page, parameter);
            }
        }

        private void NavBackRequested(muxc.NavigationView sender, muxc.NavigationViewBackRequestedEventArgs args) => TryGoBack();

        private bool TryGoBack()
        {
            if (!ContentFrame.CanGoBack)
            {
                return false;
            }

            ContentFrame.GoBack();
            return true;
        }

        /// <summary>返回 / 前进后让选中项与当前页一致（Groove 的行为：返回到首页时「首页」重新高亮）。</summary>
        private void ContentFrameNavigated(object sender, NavigationEventArgs e)
        {
            Nav.IsBackEnabled = ContentFrame.CanGoBack;

            if (e.SourcePageType == typeof(HomePage))
            {
                Nav.SelectedItem = HomeItem;
            }
            else if (e.SourcePageType == typeof(LibraryPage))
            {
                Nav.SelectedItem = LibraryItem;
            }
            else if (e.SourcePageType == typeof(SettingsPage) || e.SourcePageType == typeof(EqualizerPage))
            {
                Nav.SelectedItem = Nav.SettingsItem;
            }
            else
            {
                // 搜索结果、详情页等不对应菜单项：清掉高亮，而不是停在上一个菜单项上。
                Nav.SelectedItem = null;
            }
        }

        // ── 搜索 ──────────────────────────────────────────────────────────────

        /// <summary>
        /// 即时建议（Groove）：用户输入停顿 500ms 后取前几首歌作为建议。防抖间隔与 Android
        /// SearchViewModel 相同，不能去掉 —— 否则每敲一个字都打一次接口。
        /// </summary>
        private async void SearchTextChanged(AutoSuggestBox sender, AutoSuggestBoxTextChangedEventArgs args)
        {
            if (args.Reason != AutoSuggestionBoxTextChangeReason.UserInput)
            {
                return;
            }

            var version = ++_suggestVersion;
            var text = sender.Text.Trim();
            if (text.Length == 0)
            {
                // 清空了输入：回到搜索记录。
                await ShowHistoryAsync(version);
                return;
            }

            await System.Threading.Tasks.Task.Delay(500);
            if (version != _suggestVersion)
            {
                return; // 500ms 内又输入了：这次作废。
            }

            try
            {
                var songs = await AppServices.Search.SearchSongsAsync(text, 8);
                if (version == _suggestVersion)
                {
                    sender.ItemsSource = songs.Select(SearchSuggestion.FromSong).ToList();
                }
            }
            catch
            {
                // 建议失败不打扰用户，回车仍可进入完整搜索。
            }
        }

        private void SearchQuerySubmitted(AutoSuggestBox sender, AutoSuggestBoxQuerySubmittedEventArgs args)
        {
            _suggestVersion++;
            sender.ItemsSource = null;

            string query;
            if (args.ChosenSuggestion is SearchSuggestion suggestion)
            {
                switch (suggestion.Kind)
                {
                    case SearchSuggestionKind.Song:
                    case SearchSuggestionKind.HistorySong:
                        // 选了歌：直接播放（与搜索结果点歌相同：插到当前曲之后），并记入搜索记录。
                        PlaybackHost.PlaySong(suggestion.Song);
                        _ = AppServices.SearchHistory.AddSongAsync(suggestion.Song);
                        return;
                    case SearchSuggestionKind.HistoryAlbum:
                        AppShell.ToAlbum(suggestion.Id);
                        return;
                    case SearchSuggestionKind.HistoryArtist:
                        AppShell.ToArtist(suggestion.Id);
                        return;
                    case SearchSuggestionKind.ClearHistory:
                        sender.Text = string.Empty;
                        _ = AppServices.SearchHistory.ClearAllAsync();
                        AppShell.Notice("搜索记录已清除");
                        return;
                    default:
                        query = suggestion.Title;
                        sender.Text = query;
                        break;
                }
            }
            else
            {
                query = (args.QueryText ?? string.Empty).Trim();
            }

            if (query.Length == 0)
            {
                return;
            }

            _ = AppServices.SearchHistory.AddQueryAsync(query);
            NavigateTo(typeof(SearchPage), query);

            // 最小 / 紧凑模式下搜索框在浮出的面板里：提交后收起面板，让结果露出来。
            if (Nav.DisplayMode != muxc.NavigationViewDisplayMode.Expanded)
            {
                Nav.IsPaneOpen = false;
            }
        }

        /// <summary>搜索框获得焦点且为空时，下拉显示搜索记录（对应 Android 搜索页空查询时的历史区）。</summary>
        private async void SearchGotFocus(object sender, RoutedEventArgs e)
        {
            if (SearchBox.Text.Trim().Length == 0)
            {
                await ShowHistoryAsync(++_suggestVersion);
            }
        }

        private async void RemoveHistoryAsync(SearchSuggestion suggestion)
        {
            var history = AppServices.SearchHistory;
            try
            {
                switch (suggestion.Kind)
                {
                    case SearchSuggestionKind.Query:
                        await history.RemoveQueryAsync(suggestion.Title);
                        break;
                    case SearchSuggestionKind.HistorySong:
                        await history.RemoveAsync(Core.Search.SearchHistoryType.Song, suggestion.Id);
                        break;
                    case SearchSuggestionKind.HistoryAlbum:
                        await history.RemoveAsync(Core.Search.SearchHistoryType.Album, suggestion.Id);
                        break;
                    case SearchSuggestionKind.HistoryArtist:
                        await history.RemoveAsync(Core.Search.SearchHistoryType.Artist, suggestion.Id);
                        break;
                    default:
                        return;
                }
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }

            await ShowHistoryAsync(++_suggestVersion);
        }

        private async System.Threading.Tasks.Task ShowHistoryAsync(int version)
        {
            IReadOnlyList<SearchSuggestion> items;
            try
            {
                var history = AppServices.SearchHistory;
                items = SearchSuggestion.FromHistory(
                    await history.GetQueriesAsync(),
                    await history.GetSongsAsync(),
                    await history.GetAlbumsAsync(),
                    await history.GetArtistsAsync());
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
                return;
            }

            if (version != _suggestVersion || SearchBox.Text.Trim().Length > 0)
            {
                return; // 期间开始输入了：以即时建议为准。
            }

            SearchBox.ItemsSource = items;
            SearchBox.IsSuggestionListOpen = items.Count > 0;
        }

        private void FocusSearch()
        {
            if (Nav.DisplayMode != muxc.NavigationViewDisplayMode.Expanded)
            {
                Nav.IsPaneOpen = true;
            }

            SearchBox.Focus(FocusState.Keyboard);
        }

        // ── 账户 ──────────────────────────────────────────────────────────────

        private void UpdateAccountItem()
        {
            // 会话失效（cookie 已被清）或未登录时显示「登录」入口；只有会话有效才显示昵称。
            // 还没判定过（Unknown，启动时判定在途）时按「有凭证」处理，免得每次启动都先闪一下「登录」。
            var state = AppServices.AccountSession.State;
            var known = state is Core.Account.SessionState.Valid
                || (state is Core.Account.SessionState.Unknown && AppServices.IsLoggedIn());
            var profile = AppServices.Cache.UserProfile;

            // 账户图标固定用 XAML 里声明的系统联系人剪影（Segoe MDL2 E77B），真实头像只在设置页显示。
            AccountItem.Content = known ? (profile?.Nickname ?? "我的") : "登录";
        }

        /// <summary>会话状态变化：侧栏账户项与内容区顶部的失效横幅都要跟着变。</summary>
        private void OnSessionChanged()
        {
            UpdateAccountItem();
            UpdateSessionBar();

            // 只记状态类型与「请求层还有没有 cookie」：昵称、UID 这些用户资料不进日志。
            // 排查「收藏刷不出来 / 推荐变了」时，这行能直接区分「登录失效」与「网络问题」。
            App.Trace("session: " + Core.Account.SessionState.TypeName(AppServices.AccountSession.State)
                + " cookie=" + (AppServices.IsLoggedIn() ? "yes" : "no"));
        }

        /// <summary>播放权益提示（试听 / 音质封顶 / 跳过）：轻提示，不弹模态框、不打断播放。</summary>
        private void OnPlaybackNotice(Core.Account.PlaybackNotice notice)
        {
            string text;
            switch (notice.Kind)
            {
                case Core.Account.PlaybackNoticeKind.Trial:
                    text = Ncrust.Resources.DisplayFormat.TrialNotice(notice.TrialSeconds, notice.Reason);
                    break;
                case Core.Account.PlaybackNoticeKind.Capped:
                    text = Ncrust.Resources.DisplayFormat.QualityCapped(notice.Level, notice.Reason);
                    break;
                case Core.Account.PlaybackNoticeKind.Skipped:
                    text = Ncrust.Resources.DisplayFormat.SkippedSong(notice.SongName, notice.Reason);
                    break;
                default:
                    text = Ncrust.Resources.DisplayFormat.SkippedSongs(notice.Count);
                    break;
            }

            ShowNotice(text);
        }

        /// <summary>
        /// 会话判定：启动时一次、窗口重新激活且距上次判定 ≥ 30 分钟时一次（间隔检查在
        /// <see cref="Core.Account.SessionMonitor.RefreshAsync"/> 里）。判定结果通过
        /// <c>AppServices.SessionChanged</c> 回到这里刷新横幅与账户项。
        /// </summary>
        private static async System.Threading.Tasks.Task RefreshSessionAsync(bool force)
        {
            try
            {
                await AppServices.AccountSession.RefreshAsync(force);
            }
            catch (Exception ex)
            {
                // 判定本身出错绝不改状态：维持上一个已知状态。
                App.WriteCrashLog(ex);
            }
        }

        /// <summary>会话失效时在内容区顶部挂横幅；重新登录成功后自动消失。</summary>
        private void UpdateSessionBar() =>
            SessionBar.IsOpen = AppServices.AccountSession.State is Core.Account.SessionState.Expired;

        private void SessionBarLoginClick(object sender, RoutedEventArgs e) => LoginLauncher.Request();

        // ── 登录层 ────────────────────────────────────────────────────────────

        private void ShowLogin()
        {
            if (_login != null)
            {
                return;
            }

            _login = new LoginPage();
            _login.LoggedIn += OnLoggedIn;
            _login.CloseRequested += OnLoginClose;
            OverlayHost.Children.Add(_login);
        }

        private async void OnLoggedIn(string cookie)
        {
            CloseLogin();

            // 登录成功后立刻复核（拿到资料与 VIP），不走 30 分钟窗口。
            await AppServices.SetCookieAsync(cookie);
            await RefreshLibraryAsync();
        }

        private void OnLoginClose() => CloseLogin();

        private void CloseLogin()
        {
            if (_login == null)
            {
                return;
            }

            _login.LoggedIn -= OnLoggedIn;
            _login.CloseRequested -= OnLoginClose;
            OverlayHost.Children.Remove(_login);
            _login = null;
        }

        // ── 快捷键 ────────────────────────────────────────────────────────────

        private void RegisterAccelerators()
        {
            AddAccelerator(VirtualKey.Escape, VirtualKeyModifiers.None, (_, args) =>
            {
                if (_login != null)
                {
                    CloseLogin();
                    args.Handled = true;
                    return;
                }

                args.Handled = Player.HandleEscape();
            });
            AddAccelerator(VirtualKey.F11, VirtualKeyModifiers.None, (_, args) =>
            {
                Player.ToggleFullscreen();
                args.Handled = true;
            });
            AddAccelerator(VirtualKey.F, VirtualKeyModifiers.Control, (_, args) =>
            {
                FocusSearch();
                args.Handled = true;
            });
            AddAccelerator(VirtualKey.L, VirtualKeyModifiers.Control, (_, args) =>
            {
                Player.ToggleExpanded();
                args.Handled = true;
            });
            AddAccelerator(VirtualKey.Left, VirtualKeyModifiers.Control, (_, args) =>
            {
                PlaybackHost.Engine.Previous();
                args.Handled = true;
            });
            AddAccelerator(VirtualKey.Right, VirtualKeyModifiers.Control, (_, args) =>
            {
                PlaybackHost.Engine.Next();
                args.Handled = true;
            });
            AddAccelerator(VirtualKey.P, VirtualKeyModifiers.Control, (_, args) =>
            {
                // Groove Music 的播放 / 暂停快捷键。
                PlaybackHost.Engine.PlayPause();
                args.Handled = true;
            });
            AddAccelerator(VirtualKey.Space, VirtualKeyModifiers.None, (_, args) =>
            {
                // 焦点在文本输入或按钮上时，空格留给它们（打字 / 按下按钮）。
                if (FocusManager.GetFocusedElement() is TextBox ||
                    FocusManager.GetFocusedElement() is AutoSuggestBox ||
                    FocusManager.GetFocusedElement() is ButtonBase)
                {
                    return;
                }

                PlaybackHost.Engine.PlayPause();
                args.Handled = true;
            });
            AddAccelerator(VirtualKey.Left, VirtualKeyModifiers.Menu, (_, args) => args.Handled = TryGoBack());
        }

        private void AddAccelerator(
            VirtualKey key,
            VirtualKeyModifiers modifiers,
            TypedEventHandler<KeyboardAccelerator, KeyboardAcceleratorInvokedEventArgs> handler)
        {
            var accelerator = new KeyboardAccelerator { Key = key, Modifiers = modifiers };
            accelerator.Invoked += handler;
            KeyboardAccelerators.Add(accelerator);
        }

        /// <summary>鼠标侧键「后退」。</summary>
        private void OnCoreWindowPointerPressed(CoreWindow sender, PointerEventArgs args)
        {
            if (args.CurrentPoint.Properties.IsXButton1Pressed)
            {
                args.Handled = TryGoBack();
            }
        }
    }
}
