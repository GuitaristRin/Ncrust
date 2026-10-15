using System;
using System.Runtime.InteropServices.WindowsRuntime;
using System.Text;
using System.Threading.Tasks;
using Microsoft.Web.WebView2.Core;
using Ncrust.Core.Api;
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

        /// <summary>账户接口说这段 cookie 无效。</summary>
        private const string InvalidSessionText = "登录状态无效，请重新登录";

        /// <summary>判不了（断网 / 服务端异常）：既不能说登录成功，也不能清掉登录态。</summary>
        private const string UnknownSessionText = "网络异常，暂时无法确认登录状态，请稍后重试";

        private readonly NcmHttp _http = new NcmHttp();
        private readonly QrLoginClient _qr;
        private readonly DispatcherTimer _pollTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(2) };

        private QrLoginKey _key;
        private int _ticks;
        private bool _webInitialized;

        /// <summary>校验进行中：同一时刻只允许一次，避免多次 NavigationCompleted 并发打接口。</summary>
        private bool _checkingSession;

        /// <summary>已经判过的 cookie 串，同一个 cookie 不重复校验。</summary>
        private string _checkedCookie;

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

                // 先清掉 WebView2 的 cookie 再导航。WebView2 的 cookie 跨会话持久化，登录层又只在
                // 需要登录时出现：清晚了（或不清）的话，第一次 NavigationCompleted 就会读到上一次
                // 残留的（可能已被服务端置空的）MUSIC_U，用户还没扫码就被判定成登录成功。
                await ClearWebCookiesAsync();

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

            await CheckWebSessionAsync();
        }

        /// <summary>
        /// 网页登录：读到非空 MUSIC_U 后还要问一次账户接口才算登录成功。cookie 可能是上次残留的，
        /// 也可能被服务端置空了 —— 光看有没有这个名字，用户没扫码也会「登录成功」。
        /// </summary>
        private async Task CheckWebSessionAsync()
        {
            if (_checkingSession)
            {
                return;
            }

            var cookie = await TryReadWebCookieAsync();
            if (cookie == null || cookie == _checkedCookie)
            {
                return; // 没有非空 MUSIC_U，或这个 cookie 刚判过。
            }

            _checkingSession = true;
            _checkedCookie = cookie;
            try
            {
                switch (await AccountApi.ValidateSessionAsync(cookie))
                {
                    case SessionValidation.Valid:
                        Complete(cookie);
                        break;
                    case SessionValidation.Invalid:
                        // 失效的 cookie 留着，下次打开登录层又会「自动登录」：清掉它，留在登录页。
                        await ClearWebCookiesAsync();
                        WebStatus.Text = InvalidSessionText;
                        WebStatus.Visibility = Visibility.Visible;
                        break;
                    default:
                        // 断网：允许同一 cookie 之后再校验一次，这次先不 Complete。
                        _checkedCookie = null;
                        WebStatus.Text = UnknownSessionText;
                        WebStatus.Visibility = Visibility.Visible;
                        break;
                }
            }
            finally
            {
                _checkingSession = false;
            }
        }

        private async Task<string> TryReadWebCookieAsync()
        {
            try
            {
                var cookies = await WebLogin.CoreWebView2.CookieManager.GetCookiesAsync(CookieDomain);
                var sb = new StringBuilder();
                foreach (var cookie in cookies)
                {
                    if (sb.Length > 0)
                    {
                        sb.Append("; ");
                    }

                    sb.Append(cookie.Name).Append('=').Append(cookie.Value);
                }

                // 只认非空的 MUSIC_U：会话失效时它可能是空值（`MUSIC_U=`），按「有没有这个名字」
                // 判定会拿着一个空 cookie 直接 Complete。
                return SessionCookie.Parse(sb.ToString()).HasMusicU ? sb.ToString() : null;
            }
            catch
            {
                return null;
            }
        }

        /// <summary>
        /// 清掉 WebView2 持有的 cookie，并读回确认。留着失效或上一账号的 MUSIC_U，登录层下次打开
        /// 就会被它自动「登录」。<c>DeleteAllCookies</c> 同步返回，但 API 没有承诺「返回后发出的请求
        /// 一定看不到旧 cookie」，所以清完读一次确认，还有残留就再清一遍（最多三轮）。
        /// 真清不掉也不拦着登录：导航后读到 cookie 时还会验一次会话。
        /// </summary>
        private async Task ClearWebCookiesAsync()
        {
            if (!_webInitialized)
            {
                return;
            }

            try
            {
                var manager = WebLogin.CoreWebView2.CookieManager;
                manager.DeleteAllCookies();
                for (var attempt = 0; attempt < 2; attempt++)
                {
                    var remaining = await manager.GetCookiesAsync(CookieDomain);
                    if (remaining.Count == 0)
                    {
                        return;
                    }

                    manager.DeleteAllCookies();
                }

                // 清不干净（少见）：不拦着登录，但记一笔，免得「登录层一打开就自动登录」再出现时无从查起。
                App.WriteLog("login.log", DateTimeOffset.Now.ToString("O") + " WebView2 cookie 清不干净\r\n");
            }
            catch (Exception ex)
            {
                // 清不掉不影响本次登录，只是下次打开登录层可能又读到旧 cookie。
                App.WriteCrashLog(ex);
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
                    if (string.IsNullOrEmpty(result.Cookie))
                    {
                        QrStatus.Text = "登录成功但未取到 cookie，请重试";
                        break;
                    }

                    await CompleteQrAsync(result.Cookie);
                    break;
            }
        }

        /// <summary>803 的 cookie 也要验一次：轮询说「成功」不等于服务端已经认这个会话。</summary>
        private async Task CompleteQrAsync(string cookie)
        {
            var validation = await AccountApi.ValidateSessionAsync(cookie);
            if (validation == SessionValidation.Valid)
            {
                Complete(cookie);
                return;
            }

            QrStatus.Text = validation == SessionValidation.Invalid
                ? "登录状态无效，请点「刷新二维码」重试"
                : "网络异常，无法确认登录状态，请点「刷新二维码」重试";
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
