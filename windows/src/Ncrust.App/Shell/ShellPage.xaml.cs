using Ncrust.Login;
using Ncrust.Platform;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;

namespace Ncrust.Shell
{
    public sealed partial class ShellPage : Page
    {
        private LoginPage _login;

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
            // M0 阶段先落 PasswordVault；服务定位器落地后改为写入共享 NcmHttp 并刷新云端库。
            new PasswordVaultCredentialStore().SetCookie(cookie);
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
    }
}
