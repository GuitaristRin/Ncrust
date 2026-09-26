using System;
using Ncrust.Login;
using Ncrust.Pages;
using Windows.UI.Xaml.Controls;

namespace Ncrust.Shell
{
    public sealed partial class ShellPage : Page
    {
        private static readonly Type[] Pages =
        {
            typeof(HomePage), typeof(SearchPage), typeof(LibraryPage), typeof(UserPage),
        };

        private LoginPage _login;

        public ShellPage()
        {
            InitializeComponent();
            Sidebar.ItemsSource = new[] { "首页", "搜索", "音乐库", "我的" };
            LoginLauncher.LaunchRequested += ShowLogin;
            Sidebar.SelectedIndex = 0; // 触发 SelectionChanged → 进入首页
        }

        private void SidebarSelectionChanged(object sender, SelectionChangedEventArgs e)
        {
            var index = Sidebar.SelectedIndex;
            if (index >= 0 && index < Pages.Length && ContentFrame.CurrentSourcePageType != Pages[index])
            {
                ContentFrame.Navigate(Pages[index]);
            }
        }

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
    }
}
