using Ncrust.Login;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;

namespace Ncrust.Pages
{
    public sealed partial class UserPage : Page
    {
        public UserPage() => InitializeComponent();

        private void LoginClick(object sender, RoutedEventArgs e) => LoginLauncher.Request();
    }
}
