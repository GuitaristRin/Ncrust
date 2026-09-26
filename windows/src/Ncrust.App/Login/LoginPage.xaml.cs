using System;
using System.Linq;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Text;
using System.Threading.Tasks;
using Microsoft.Web.WebView2.Core;
using Ncrust.Core.Auth;
using Ncrust.Core.Net;
using QRCoder;
using Windows.Storage.Streams;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Media.Imaging;

namespace Ncrust.Login
{
    /// <summary>
    /// 独立登录层：内嵌 WebView2 登录（主）+ 二维码（辅）。成功后回传 cookie 串。
    /// </summary>
    public sealed partial class LoginPage : Page
    {
        private const string LoginUrl = "https://music.163.com/#/login";
        private const string CookieDomain = "https://music.163.com";
        private const int MaxPollTicks = 150;

        private readonly NcmHttp _http = new NcmHttp();
        private readonly QrLoginClient _qr;
        private readonly DispatcherTimer _pollTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };

        private QrLoginKey _key;
        private int _ticks;
        private bool _webInitialized;

        public event Action<string> LoggedIn;

        public event Action CloseRequested;

        public LoginPage()
        {
            InitializeComponent();
            _qr = new QrLoginClient(_http);
            Loaded += OnLoaded;
            _pollTimer.Tick += OnPollTick;
        }

        private async void OnLoaded(object sender, RoutedEventArgs e)
        {
            // 只初始化当前可见的网页登录；二维码等切到「扫码登录」时再取，
            // 否则后台一直轮询一个用户没看到的二维码。
            await InitWebViewAsync();
        }

        private async Task InitWebViewAsync()
        {
            if (_webInitialized)
            {
                return;
            }

            try
            {
                await WebLogin.EnsureCoreWebView2Async();
                _webInitialized = true;
                WebLogin.CoreWebView2.NavigationCompleted += OnNavigationCompleted;
                WebLogin.CoreWebView2.Navigate(LoginUrl);
            }
            catch (Exception ex)
            {
                // WebView2 运行时缺失或初始化失败：不要静默留一片空白，提示改用扫码。
                App.WriteCrashLog(ex);
                WebStatus.Text = "网页登录不可用（WebView2 初始化失败）。请点右上角「扫码登录」。";
                WebStatus.Visibility = Visibility.Visible;
            }
        }

        private async void OnNavigationCompleted(object sender, CoreWebView2NavigationCompletedEventArgs args)
        {
            if (args.IsSuccess)
            {
                WebStatus.Visibility = Visibility.Collapsed;
            }
            else
            {
                WebStatus.Text = "登录页加载失败，请检查网络，或点右上角「扫码登录」。";
                WebStatus.Visibility = Visibility.Visible;
            }

            var cookie = await TryReadWebCookieAsync();
            if (cookie != null)
            {
                Complete(cookie);
            }
        }

        private async Task<string> TryReadWebCookieAsync()
        {
            try
            {
                var cookies = await WebLogin.CoreWebView2.CookieManager.GetCookiesAsync(CookieDomain);
                if (!cookies.Any(cookie => cookie.Name == "MUSIC_U"))
                {
                    return null;
                }

                var sb = new StringBuilder();
                foreach (var cookie in cookies)
                {
                    if (sb.Length > 0)
                    {
                        sb.Append("; ");
                    }

                    sb.Append(cookie.Name).Append('=').Append(cookie.Value);
                }

                return sb.ToString();
            }
            catch
            {
                return null;
            }
        }

        private async Task StartQrAsync()
        {
            _pollTimer.Stop();
            _key = null;
            QrImage.Source = null;
            QrStatus.Text = "正在获取二维码…";

            try
            {
                _key = await _qr.RequestKeyAsync();
            }
            catch
            {
                _key = null;
            }

            if (_key == null)
            {
                QrStatus.Text = "获取二维码失败，点「刷新二维码」重试";
                return;
            }

            QrImage.Source = await RenderQrAsync(_key);
            QrStatus.Text = "请用网易云 App 扫码";
            _ticks = 0;
            _pollTimer.Start();
        }

        private static async Task<BitmapImage> RenderQrAsync(QrLoginKey key)
        {
            // 服务端给了图就直接用，否则本地生成。
            if (!string.IsNullOrEmpty(key.QrImage))
            {
                var base64 = key.QrImage.Contains(",")
                    ? key.QrImage.Substring(key.QrImage.IndexOf(',') + 1)
                    : key.QrImage;
                try
                {
                    return await BytesToImageAsync(Convert.FromBase64String(base64));
                }
                catch
                {
                    // 落到本地生成。
                }
            }

            byte[] png;
            using (var generator = new QRCodeGenerator())
            using (var data = generator.CreateQrCode(key.QrUrl, QRCodeGenerator.ECCLevel.M))
            {
                png = new PngByteQRCode(data).GetGraphic(10);
            }

            return await BytesToImageAsync(png);
        }

        /// <summary>
        /// 必须真异步：SetSourceAsync 要回到 UI 线程完成，在 UI 线程上 .Wait() 它会死锁
        /// （曾导致打开登录层后整个应用无响应、WebView2 一片空白）。
        /// </summary>
        private static async Task<BitmapImage> BytesToImageAsync(byte[] bytes)
        {
            var image = new BitmapImage();
            using (var stream = new InMemoryRandomAccessStream())
            {
                await stream.WriteAsync(bytes.AsBuffer());
                stream.Seek(0);
                await image.SetSourceAsync(stream);
            }

            return image;
        }

        private async void OnPollTick(object sender, object e)
        {
            if (_key == null)
            {
                return;
            }

            _ticks++;
            if (_ticks > MaxPollTicks)
            {
                _pollTimer.Stop();
                QrStatus.Text = "二维码已过期，点「刷新二维码」";
                return;
            }

            QrLoginPollResult result;
            try
            {
                result = await _qr.PollAsync(_key.Unikey, _key.SDeviceId);
            }
            catch
            {
                return; // 单次轮询失败继续。
            }

            switch (result.Code)
            {
                case QrLoginClient.Scanned:
                    QrStatus.Text = "已扫码，请在手机上确认";
                    break;
                case QrLoginClient.Expired:
                    _pollTimer.Stop();
                    QrStatus.Text = "二维码已过期，点「刷新二维码」";
                    break;
                case QrLoginClient.Succeeded:
                    _pollTimer.Stop();
                    if (!string.IsNullOrEmpty(result.Cookie))
                    {
                        Complete(result.Cookie);
                    }
                    else
                    {
                        QrStatus.Text = "登录成功但未取到 cookie，请重试";
                    }

                    break;
            }
        }

        private void Complete(string cookie)
        {
            _pollTimer.Stop();
            LoggedIn?.Invoke(cookie);
        }

        private void ShowWebClick(object sender, RoutedEventArgs e)
        {
            WebLogin.Visibility = Visibility.Visible;
            WebStatus.Visibility = _webInitialized ? Visibility.Collapsed : Visibility.Visible;
            QrPanel.Visibility = Visibility.Collapsed;
            _ = InitWebViewAsync();
        }

        private void ShowQrClick(object sender, RoutedEventArgs e)
        {
            QrPanel.Visibility = Visibility.Visible;
            WebLogin.Visibility = Visibility.Collapsed;
            WebStatus.Visibility = Visibility.Collapsed;
            if (_key == null)
            {
                _ = StartQrAsync();
            }
        }

        private void RefreshQrClick(object sender, RoutedEventArgs e) => _ = StartQrAsync();

        private void CloseClick(object sender, RoutedEventArgs e) => CloseRequested?.Invoke();
    }
}
