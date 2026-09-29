using System;
using System.Net.Http;
using System.Threading.Tasks;
using Ncrust.Core.Update;
using Windows.ApplicationModel;
using Windows.Storage;
using Windows.System;

namespace Ncrust.Update
{
    /// <summary>
    /// 应用内更新（对应 <c>windows/AGENTS.md</c>「Windows 产物与更新」）：
    /// 查 GitHub Releases API，用 <see cref="UpdateChecker"/> 找出比本机更高的 Windows 版本；
    /// 用户同意后下载 <c>.appinstaller</c> 并交给系统的 App Installer 完成安装。
    ///
    /// UWP 无法静默自装，所以「下拉应用」= 应用内下载 + 拉起 App Installer；真正的
    /// 后台自动更新由 `.appinstaller` 的 <c>UpdateSettings</c> 负责（须经它首次安装）。
    /// </summary>
    internal static class UpdateService
    {
        private const string ReleasesApi =
            "https://api.github.com/repos/GuitaristRin/Ncrust/releases?per_page=30";

        private static readonly TimeSpan CacheWindow = TimeSpan.FromHours(6);

        private static readonly HttpClient Http = CreateHttp();

        private static DateTimeOffset _lastCheckedAt = DateTimeOffset.MinValue;
        private static UpdateInfo _cached;

        public static AppVersion CurrentVersion { get; } = ReadCurrentVersion();

        /// <summary>查一次更新。<paramref name="force"/> 为 false 时 6 小时内复用上次结果；失败返回 null。</summary>
        public static async Task<UpdateInfo> CheckAsync(bool force)
        {
            if (!force && DateTimeOffset.UtcNow - _lastCheckedAt < CacheWindow)
            {
                return _cached;
            }

            var result = await FetchAsync();
            _lastCheckedAt = DateTimeOffset.UtcNow;
            _cached = result;
            return result;
        }

        /// <summary>
        /// 下载更新包并交给系统安装。优先 <c>.appinstaller</c>（能保留后续自动更新源），退回 <c>.msix</c>。
        /// 文件写入临时目录后 <see cref="Launcher.LaunchFileAsync"/>；再不行就用系统默认程序打开 URL。
        /// </summary>
        public static async Task<bool> DownloadAndLaunchAsync(UpdateInfo info)
        {
            var url = info?.AppInstallerUrl ?? info?.MsixUrl;
            if (string.IsNullOrEmpty(url))
            {
                return false;
            }

            try
            {
                var name = url!.EndsWith(".appinstaller", StringComparison.OrdinalIgnoreCase)
                    ? "ncrust-update.appinstaller"
                    : "ncrust-update.msix";
                var file = await ApplicationData.Current.TemporaryFolder
                    .CreateFileAsync(name, CreationCollisionOption.ReplaceExisting);
                using (var response = await Http.GetAsync(url))
                {
                    response.EnsureSuccessStatusCode();
                    var bytes = await response.Content.ReadAsByteArrayAsync();
                    await FileIO.WriteBytesAsync(file, bytes);
                }

                return await Launcher.LaunchFileAsync(file);
            }
            catch
            {
                try
                {
                    return await Launcher.LaunchUriAsync(new Uri(url!));
                }
                catch
                {
                    return false;
                }
            }
        }

        private static async Task<UpdateInfo> FetchAsync()
        {
            try
            {
                using (var request = new HttpRequestMessage(HttpMethod.Get, ReleasesApi))
                {
                    request.Headers.TryAddWithoutValidation("User-Agent", "Ncrust-Windows");
                    request.Headers.TryAddWithoutValidation("Accept", "application/vnd.github+json");
                    using (var response = await Http.SendAsync(request).ConfigureAwait(false))
                    {
                        if (!response.IsSuccessStatusCode)
                        {
                            return null;
                        }

                        var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                        return UpdateChecker.Find(body, CurrentVersion);
                    }
                }
            }
            catch
            {
                return null;
            }
        }

        private static AppVersion ReadCurrentVersion()
        {
            var v = Package.Current.Id.Version;
            return new AppVersion((int)v.Major, (int)v.Minor, (int)v.Build, (int)v.Revision);
        }

        private static HttpClient CreateHttp()
        {
            var client = new HttpClient { Timeout = TimeSpan.FromSeconds(30) };
            return client;
        }
    }
}
