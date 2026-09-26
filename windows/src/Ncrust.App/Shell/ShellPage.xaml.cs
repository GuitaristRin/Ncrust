using System;
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
        private LoginPage _login;
        private int _suggestVersion;

        public ShellPage()
        {
            InitializeComponent();
            SetUpTitleBar();
            RegisterAccelerators();

            // 快捷键不在页面上弹出按键提示。
            KeyboardAcceleratorPlacementMode = KeyboardAcceleratorPlacementMode.Hidden;

            LoginLauncher.LaunchRequested += ShowLogin;
            Window.Current.CoreWindow.PointerPressed += OnCoreWindowPointerPressed;
            Loaded += OnLoaded;

            Nav.SelectedItem = HomeItem;
            ContentFrame.Navigate(typeof(HomePage));
        }

        private async void OnLoaded(object sender, RoutedEventArgs e)
        {
            UpdateAccountItem();
            await PlaybackHost.RestoreAsync();
            await RefreshProfileAsync();
        }

        // ── 标题栏 ────────────────────────────────────────────────────────────

        private void SetUpTitleBar()
        {
            var coreTitleBar = CoreApplication.GetCurrentView().TitleBar;
            coreTitleBar.ExtendViewIntoTitleBar = true;
            coreTitleBar.LayoutMetricsChanged += (bar, _) => ApplyTitleBarHeight(bar.Height);
            ApplyTitleBarHeight(coreTitleBar.Height > 0 ? coreTitleBar.Height : 32);
            Window.Current.SetTitleBar(AppTitleBar);

            // 系统标题栏按钮（最小化 / 最大化 / 关闭）：透明底，前景跟随深色主题。
            var titleBar = ApplicationView.GetForCurrentView().TitleBar;
            titleBar.ButtonBackgroundColor = Colors.Transparent;
            titleBar.ButtonInactiveBackgroundColor = Colors.Transparent;
            titleBar.ButtonForegroundColor = Colors.White;
            titleBar.ButtonInactiveForegroundColor = Color.FromArgb(0xFF, 0x80, 0x80, 0x80);
            titleBar.ButtonHoverBackgroundColor = Color.FromArgb(0x22, 0xFF, 0xFF, 0xFF);
            titleBar.ButtonHoverForegroundColor = Colors.White;
            titleBar.ButtonPressedBackgroundColor = Color.FromArgb(0x44, 0xFF, 0xFF, 0xFF);
            titleBar.ButtonPressedForegroundColor = Colors.White;

            // SetPreferredMinSize 的上限是 500x500。
            ApplicationView.GetForCurrentView().SetPreferredMinSize(new Size(360, 500));

            Window.Current.Activated += (_, args) =>
                AppTitle.Opacity = args.WindowActivationState == CoreWindowActivationState.Deactivated ? 0.5 : 1.0;
        }

        private void ApplyTitleBarHeight(double height)
        {
            AppTitleBar.Height = height;

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
                    if (AppServices.IsLoggedIn())
                    {
                        NavigateTo(typeof(UserPage));
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
            else if (e.SourcePageType == typeof(UserPage))
            {
                Nav.SelectedItem = AccountItem;
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
                sender.ItemsSource = null;
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
                    sender.ItemsSource = songs;
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

            // 选了建议里的歌：直接播放（与搜索结果点歌相同：插到当前曲之后）。
            if (args.ChosenSuggestion is Core.Api.SongItem song)
            {
                PlaybackHost.PlaySong(song);
                _ = AppServices.SearchHistory.AddSongAsync(song);
                return;
            }

            var query = (args.QueryText ?? string.Empty).Trim();
            if (query.Length == 0)
            {
                return;
            }

            NavigateTo(typeof(SearchPage), query);

            // 最小 / 紧凑模式下搜索框在浮出的面板里：提交后收起面板，让结果露出来。
            if (Nav.DisplayMode != muxc.NavigationViewDisplayMode.Expanded)
            {
                Nav.IsPaneOpen = false;
            }
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
            var profile = AppServices.Cache.UserProfile;
            var loggedIn = AppServices.IsLoggedIn();

            AccountItem.Content = loggedIn ? (profile?.Nickname ?? "我的") : "登录";
            AccountItem.SelectsOnInvoked = loggedIn;

            if (loggedIn && !string.IsNullOrEmpty(profile?.AvatarUrl))
            {
                AccountItem.Icon = new BitmapIcon
                {
                    UriSource = new Uri(profile.AvatarUrl + "?param=64y64"),
                    ShowAsMonochrome = false,
                };
            }
            else
            {
                AccountItem.Icon = new FontIcon { FontFamily = new Windows.UI.Xaml.Media.FontFamily("Segoe MDL2 Assets"), Glyph = "\uE77B" };
            }
        }

        private async System.Threading.Tasks.Task RefreshProfileAsync()
        {
            if (!AppServices.IsLoggedIn())
            {
                return;
            }

            try
            {
                var profile = await AppServices.Account.GetProfileAsync();
                if (profile != null)
                {
                    AppServices.Cache.UserProfile = profile;
                }
            }
            catch (Exception ex)
            {
                // 网络失败时保留「我的」占位，不打断启动。
                App.WriteCrashLog(ex);
            }

            UpdateAccountItem();
        }

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
            AppServices.SetCookie(cookie);
            CloseLogin();
            await RefreshProfileAsync();

            try
            {
                await AppServices.Library.RefreshFromCloudAsync();
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }
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
