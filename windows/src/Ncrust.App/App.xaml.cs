using System;
using System.Text;
using Ncrust.Shell;
using Windows.ApplicationModel;
using Windows.ApplicationModel.Activation;
using Windows.Storage;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Navigation;

namespace Ncrust
{
    /// <summary>应用入口。</summary>
    sealed partial class App : Application
    {
        public App()
        {
            InitializeComponent();

            // 未处理异常落盘到 LocalFolder/crash.log。
            // UWP 的未处理异常默认只进事件日志，而那里的 .NET Runtime 条目不含异常消息，
            // 一个 XAML 资源键写错导致的崩溃会完全无从查起。
            UnhandledException += (_, e) => WriteCrashLog(e.Exception);
            Suspending += OnSuspending;
        }

        internal static void WriteCrashLog(Exception ex)
        {
            try
            {
                var sb = new StringBuilder();
                sb.AppendLine($"--- {DateTimeOffset.Now:O} ---");
                sb.AppendLine($"{ex?.GetType().FullName}: {ex?.Message}");

                var inner = ex?.InnerException;
                for (var i = 0; inner != null && i < 6; i++)
                {
                    sb.AppendLine($"  [inner {i}] {inner.GetType().Name}: {inner.Message}");
                    inner = inner.InnerException;
                }

                sb.AppendLine(ex?.StackTrace);
                sb.AppendLine();

                var path = System.IO.Path.Combine(ApplicationData.Current.LocalFolder.Path, "crash.log");
                System.IO.File.AppendAllText(path, sb.ToString());
            }
            catch
            {
                // 写崩溃日志本身不能再抛，否则可诊断的失败会变成不可诊断的失败。
            }
        }

        protected override void OnLaunched(LaunchActivatedEventArgs e)
        {
            if (!(Window.Current.Content is Frame rootFrame))
            {
                rootFrame = new Frame();
                rootFrame.NavigationFailed += OnNavigationFailed;
                Window.Current.Content = rootFrame;
            }

            if (!e.PrelaunchActivated && rootFrame.Content == null)
            {
                rootFrame.Navigate(typeof(ShellPage), e.Arguments);
            }

            Window.Current.Activate();
        }

        private void OnNavigationFailed(object sender, NavigationFailedEventArgs e)
        {
            var ex = new Exception($"无法导航到页面 {e.SourcePageType?.FullName}", e.Exception);
            WriteCrashLog(ex);
            throw ex;
        }

        private void OnSuspending(object sender, SuspendingEventArgs e)
        {
            var deferral = e.SuspendingOperation.GetDeferral();
            deferral.Complete();
        }
    }
}
