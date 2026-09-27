using System;
using System.Globalization;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Audio;
using Ncrust.Login;
using Ncrust.Playback;
using Ncrust.Shell;
using Windows.ApplicationModel;
using Windows.Storage;
using Windows.System;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Media.Imaging;
using Windows.UI.Xaml.Navigation;

namespace Ncrust.Pages
{
    /// <summary>设置页（对应 Android UserScreen）。设置键与 Android 相同（wifi_quality / gapless_playback / theme_mode…）。</summary>
    public sealed partial class SettingsPage : Page
    {
        /// <summary>与 Android strings.qualityOptions 一致，下标即持久化值（0..6）。</summary>
        private static readonly string[] QualityOptions = { "压缩", "较好", "更好", "无损", "高解析", "高清环绕声", "杜比全景声" };

        private bool _loading;

        public SettingsPage()
        {
            InitializeComponent();
            foreach (var option in QualityOptions)
            {
                WifiQualityBox.Items.Add(option);
                MeteredQualityBox.Items.Add(option);
            }

            AppServices.SessionChanged += UpdateAccount;
            VersionText.Text = "Ncrust " + FormatVersion(Package.Current.Id.Version);
        }

        protected override void OnNavigatedTo(NavigationEventArgs e)
        {
            base.OnNavigatedTo(e);

            // 从均衡器子页回来时刷新摘要；其余控件按当前设置回填（回填期间不触发写入）。
            _loading = true;
            var prefs = AppServices.PlayPrefs;
            WifiQualityBox.SelectedIndex = Clamp(prefs.WifiQualityIndex);
            MeteredQualityBox.SelectedIndex = Clamp(prefs.MobileQualityIndex);
            GaplessSwitch.IsOn = prefs.GaplessEnabled;
            TranslationSwitch.IsOn = prefs.LyricsTranslation;
            ThemeSystemRadio.IsChecked = AppTheme.Mode == AppThemeMode.System;
            ThemeDarkRadio.IsChecked = AppTheme.Mode == AppThemeMode.Dark;
            ThemeLightRadio.IsChecked = AppTheme.Mode == AppThemeMode.Light;
            _loading = false;

            UpdateAccount();
            UpdateEqualizerSummary();
            _ = UpdateCacheSizeAsync();
        }

        // ── 账户 ──────────────────────────────────────────────────────────────

        private void UpdateAccount()
        {
            var loggedIn = AppServices.IsLoggedIn();
            AccountLoggedIn.Visibility = loggedIn ? Visibility.Visible : Visibility.Collapsed;
            AccountLoggedOut.Visibility = loggedIn ? Visibility.Collapsed : Visibility.Visible;

            var profile = AppServices.Cache.UserProfile;
            NicknameText.Text = profile?.Nickname ?? "我的";
            UidText.Text = profile != null && profile.UserId > 0
                ? "UID: " + profile.UserId.ToString(CultureInfo.InvariantCulture)
                : string.Empty;

            var avatar = CoverUrls.WithSize(profile?.AvatarUrl, 128);
            AvatarImage.Source = avatar != null && Uri.TryCreate(avatar, UriKind.Absolute, out var uri)
                ? new BitmapImage(uri) { DecodePixelWidth = 128 }
                : null;
        }

        private void LoginClick(object sender, RoutedEventArgs e) => LoginLauncher.Request();

        private async void LogoutClick(object sender, RoutedEventArgs e)
        {
            var dialog = new ContentDialog
            {
                Title = "退出登录",
                Content = "退出后本机的每日推荐与云端同步将不可用，播放队列与已缓存的音乐库保留。",
                PrimaryButtonText = "退出登录",
                CloseButtonText = "取消",
                DefaultButton = ContentDialogButton.Close,
            };

            if (await dialog.ShowAsync() == ContentDialogResult.Primary)
            {
                AppServices.SignOut();
            }
        }

        // ── 音质 / 播放 ───────────────────────────────────────────────────────

        private void WifiQualityChanged(object sender, SelectionChangedEventArgs e)
        {
            if (!_loading && WifiQualityBox.SelectedIndex >= 0)
            {
                AppServices.PlayPrefs.WifiQualityIndex = WifiQualityBox.SelectedIndex;
            }
        }

        private void MeteredQualityChanged(object sender, SelectionChangedEventArgs e)
        {
            if (!_loading && MeteredQualityBox.SelectedIndex >= 0)
            {
                AppServices.PlayPrefs.MobileQualityIndex = MeteredQualityBox.SelectedIndex;
            }
        }

        private void GaplessToggled(object sender, RoutedEventArgs e)
        {
            if (!_loading)
            {
                AppServices.PlayPrefs.GaplessEnabled = GaplessSwitch.IsOn;
            }
        }

        private void TranslationToggled(object sender, RoutedEventArgs e)
        {
            if (!_loading)
            {
                AppServices.PlayPrefs.LyricsTranslation = TranslationSwitch.IsOn;
                Player.PlayerHost.NotifyLyricsSettingsChanged();
            }
        }

        // ── 音效 ──────────────────────────────────────────────────────────────

        private void UpdateEqualizerSummary()
        {
            if (!PlaybackHost.Engine.EqualizerAvailable)
            {
                EqualizerSummary.Text = "当前系统不支持挂载音效，均衡器不可用";
                return;
            }

            var state = AppServices.Equalizer.LoadState();
            EqualizerSummary.Text = state.Enabled
                ? "已开启 · " + (EqualizerPage.DescribePreset(state) ?? "自定义")
                : "已关闭";
        }

        private void EqualizerClick(object sender, RoutedEventArgs e) => Frame.Navigate(typeof(EqualizerPage));

        // ── 外观 ──────────────────────────────────────────────────────────────

        private void ThemeChecked(object sender, RoutedEventArgs e)
        {
            if (_loading || !(sender is RadioButton radio) || !(radio.Tag is string tag))
            {
                return;
            }

            if (Enum.TryParse<AppThemeMode>(tag, out var mode) && mode != AppTheme.Mode)
            {
                AppTheme.Set(mode);
            }
        }

        private async void OpenColorSettingsClick(object sender, RoutedEventArgs e) =>
            await Launcher.LaunchUriAsync(new Uri("ms-settings:colors"));

        // ── 存储与缓存 ────────────────────────────────────────────────────────

        private async Task UpdateCacheSizeAsync()
        {
            var bytes = await Task.Run(() => CacheFolders().Sum(FolderSize) + FileSize(LyricsCacheFile()));
            CacheSizeText.Text = "缓存占用 " + FormatBytes(bytes);
        }

        private async void ClearCacheClick(object sender, RoutedEventArgs e)
        {
            var dialog = new ContentDialog
            {
                Title = "清除缓存",
                Content = "确定清除全部缓存？",
                PrimaryButtonText = "清除",
                CloseButtonText = "取消",
                DefaultButton = ContentDialogButton.Close,
            };

            if (await dialog.ShowAsync() != ContentDialogResult.Primary)
            {
                return;
            }

            ClearCacheButton.IsEnabled = false;
            AppServices.Cache.ClearAll();
            await AppServices.Lyrics.ClearAsync();
            await Task.Run(() =>
            {
                foreach (var folder in CacheFolders())
                {
                    ClearFolder(folder);
                }
            });

            await UpdateCacheSizeAsync();
            CacheSizeText.Text += " · 缓存已清除";
            ClearCacheButton.IsEnabled = true;
        }

        /// <summary>
        /// 可清理的缓存目录：LocalCache、Temp，以及应用自己的 WinINet 缓存（BitmapImage 下载的封面在这里）。
        /// 不含 LocalState（登录 WebView2 数据、音乐库、播放队列都在那里）。
        /// </summary>
        private static string[] CacheFolders()
        {
            var data = ApplicationData.Current;
            var package = Directory.GetParent(data.LocalFolder.Path)?.FullName;
            return new[]
            {
                data.LocalCacheFolder.Path,
                data.TemporaryFolder.Path,
                package == null ? null : Path.Combine(package, "AC", "INetCache"),
            }.Where(p => p != null).ToArray();
        }

        private static string LyricsCacheFile() => Path.Combine(ApplicationData.Current.LocalFolder.Path, "lyrics_cache.json");

        private static long FolderSize(string path)
        {
            try
            {
                return Directory.Exists(path)
                    ? new DirectoryInfo(path).EnumerateFiles("*", SearchOption.AllDirectories).Sum(f => SafeLength(f))
                    : 0;
            }
            catch
            {
                return 0; // 没有权限枚举的目录按 0 计。
            }
        }

        private static long SafeLength(FileInfo file)
        {
            try
            {
                return file.Length;
            }
            catch
            {
                return 0;
            }
        }

        private static long FileSize(string path)
        {
            try
            {
                return File.Exists(path) ? new FileInfo(path).Length : 0;
            }
            catch
            {
                return 0;
            }
        }

        private static void ClearFolder(string path)
        {
            try
            {
                if (!Directory.Exists(path))
                {
                    return;
                }

                foreach (var file in new DirectoryInfo(path).EnumerateFiles("*", SearchOption.AllDirectories))
                {
                    try
                    {
                        file.Delete();
                    }
                    catch
                    {
                        // 正被占用的文件跳过（例如正在显示的封面）。
                    }
                }
            }
            catch
            {
            }
        }

        // ── 格式化 ────────────────────────────────────────────────────────────

        private static int Clamp(int index) => Math.Max(0, Math.Min(QualityOptions.Length - 1, index));

        private static string FormatVersion(PackageVersion v) =>
            string.Format(CultureInfo.InvariantCulture, "{0}.{1}.{2}", v.Major, v.Minor, v.Build);

        /// <summary>与 Android formatCacheBytes 同样的分档：B / KB / MB / GB。</summary>
        private static string FormatBytes(long bytes)
        {
            if (bytes < 1024)
            {
                return bytes.ToString(CultureInfo.InvariantCulture) + " B";
            }

            double value = bytes;
            string[] units = { "KB", "MB", "GB" };
            var unit = -1;
            while (value >= 1024 && unit < units.Length - 1)
            {
                value /= 1024;
                unit++;
            }

            return value.ToString("0.#", CultureInfo.InvariantCulture) + " " + units[unit];
        }
    }
}
