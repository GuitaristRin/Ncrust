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
            Trace("App ctor: enter");

            // 先把处理器挂上：App.xaml 资源解析失败会在 InitializeComponent 里抛出，
            // 那时若还没挂钩就会变成「无日志闪退」。
            UnhandledException += (_, e) => WriteCrashLog(e.Exception);
            AppDomain.CurrentDomain.UnhandledException += (_, e) => WriteCrashLog(e.ExceptionObject as Exception);
            System.Threading.Tasks.TaskScheduler.UnobservedTaskException += (_, e) => WriteCrashLog(e.Exception);

            try
            {
                InitializeComponent();
            }
            catch (Exception ex)
            {
                WriteCrashLog(ex);
                throw;
            }

            Trace("App ctor: after InitializeComponent");
            Suspending += OnSuspending;
        }

        internal static void Trace(string message) =>
            WriteLog("boot.log", $"{DateTimeOffset.Now:HH:mm:ss.fff} {message}\r\n");

        /// <summary>三个落盘点：LocalFolder 在极早期可能不可用，Environment 与 GetTempPath 兜底。</summary>
        internal static void WriteLog(string fileName, string text)
        {
            try
            {
                var path = System.IO.Path.Combine(ApplicationData.Current.LocalFolder.Path, fileName);
                System.IO.File.AppendAllText(path, text);
            }
            catch
            {
            }

            try
            {
                var dir = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
                System.IO.File.AppendAllText(System.IO.Path.Combine(dir, "ncrust-" + fileName), text);
            }
            catch
            {
            }

            try
            {
                System.IO.File.AppendAllText(System.IO.Path.Combine(System.IO.Path.GetTempPath(), "ncrust-" + fileName), text);
            }
            catch
            {
            }
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

                WriteLog("crash.log", sb.ToString());
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

            // 强调色跟随 Windows（读不到时用内置云杉绿），在第一个页面构建前同步好。
            Kanesumi.Xaml.KanesumiAccent.FollowSystem(Resources);

            Trace("OnLaunched: before Navigate");
            if (!e.PrelaunchActivated && rootFrame.Content == null)
            {
                rootFrame.Navigate(typeof(ShellPage), e.Arguments);
            }

            Trace("OnLaunched: after Navigate");
            Window.Current.Activate();
            Trace("OnLaunched: after Activate");
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
