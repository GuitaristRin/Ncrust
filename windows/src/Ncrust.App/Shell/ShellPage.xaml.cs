using System.Threading.Tasks;
using Ncrust.Login;
using Ncrust.Playback;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;

namespace Ncrust.Shell
{
    public sealed partial class ShellPage : Page
    {
        // 固定的测试曲（晴天），M0 播放验证用；正式页面接入后再替换。
        private const long TestSongId = 186016;

        private LoginPage _login;
        private PlaybackEngine _engine;

        public ShellPage()
        {
            InitializeComponent();
        }

        private void LoginClick(object sender, RoutedEventArgs e)
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

        private void OnLoggedIn(string cookie)
        {
            AppServices.SetCookie(cookie);
            CloseLogin();
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

        private void EnsureEngine()
        {
            if (_engine != null)
            {
                return;
            }

            _engine = new PlaybackEngine(
                AppServices.UrlResolver,
                AppServices.Session,
                AppServices.PlayPrefs,
                AppServices.Network,
                AppServices.Http);
            _engine.Initialize();
            _engine.CurrentSongChanged += song =>
                CurrentText.Text = song.Name + " · " + (song.Artists.Count > 0 ? song.Artists[0].Name : string.Empty);
            _engine.PlaybackError += _ => CurrentText.Text = "播放失败（无可用音质或缺登录）";
        }

        private async void PlayTestClick(object sender, RoutedEventArgs e)
        {
            EnsureEngine();
            CurrentText.Text = "加载中…";

            var songs = await AppServices.Songs.GetSongDetailAsync(new[] { TestSongId });
            if (songs.Count == 0)
            {
                CurrentText.Text = "取歌曲信息失败";
                return;
            }

            AppServices.Queue.ReplaceAll(songs);
            _engine.PlayCurrent();
        }

        private void PlayPauseClick(object sender, RoutedEventArgs e)
        {
            EnsureEngine();
            _engine.PlayPause();
        }

        private void NextClick(object sender, RoutedEventArgs e)
        {
            EnsureEngine();
            _engine.Next();
        }
    }
}
