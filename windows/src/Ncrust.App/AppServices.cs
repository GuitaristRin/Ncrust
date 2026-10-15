using Ncrust.Core.Account;
using Ncrust.Core.Api;
using Ncrust.Core.Audio;
using Ncrust.Core.Auth;
using Ncrust.Core.Cache;
using Ncrust.Core.Library;
using Ncrust.Core.Lyrics;
using Ncrust.Core.Net;
using Ncrust.Core.Platform;
using Ncrust.Core.Playback;
using Ncrust.Core.Search;
using Ncrust.Platform;
using Windows.ApplicationModel.Core;
using Windows.UI.Core;

namespace Ncrust
{
    /// <summary>
    /// 与 Android 一样用单例充当服务定位器（无 DI 框架）。懒加载，首次访问才构造。
    /// </summary>
    internal static class AppServices
    {
        public static ISettingsStore Settings { get; } = new LocalSettingsStore();

        public static IFileStore Files { get; } = new LocalFileStore();

        public static ICredentialStore Credentials { get; } = new PasswordVaultCredentialStore();

        public static ICodecProbe CodecProbe { get; } = new WindowsCodecProbe();

        public static INetworkInfo Network { get; } = new ConnectionProfileNetworkInfo();

        /// <summary>xeapi 的设备 id / 公钥 / 会话。设备 id 与公钥落 <see cref="Settings"/>，跨启动复用。</summary>
        public static XeapiState Xeapi { get; } = new XeapiState(Settings);

        public static NcmHttp Http { get; } = CreateHttp();

        public static PlaybackPreferences PlayPrefs { get; } = new PlaybackPreferences(Settings);

        public static PlaybackQueue Queue { get; } = new PlaybackQueue();

        public static PlaybackSessionState Session { get; } =
            new PlaybackSessionState(Queue, PlayPrefs, new PlaybackStateStore(Files));

        public static SongUrlResolver UrlResolver { get; } = new SongUrlResolver(Http, CodecProbe);

        public static ContentCache Cache { get; } = new ContentCache();

        public static SearchHistory SearchHistory { get; } = new SearchHistory(Files);

        public static LyricsCache Lyrics { get; } = new LyricsCache(Files);

        public static LibraryManager Library { get; } =
            new LibraryManager(Files, new ApiLibraryCloud(Http), IsLoggedIn);

        public static DiscoveryApi Discovery { get; } = new DiscoveryApi(Http);

        public static SearchApi Search { get; } = new SearchApi(Http);

        public static SongApi Songs { get; } = new SongApi(Http);

        public static PlaylistApi Playlists { get; } = new PlaylistApi(Http);

        public static AlbumApi Albums { get; } = new AlbumApi(Http);

        public static ArtistApi Artists { get; } = new ArtistApi(Http);

        public static AccountApi Account { get; } = new AccountApi(Http);

        public static QrLoginClient Qr { get; } = new QrLoginClient(Http);

        public static EqualizerStore Equalizer { get; } = new EqualizerStore(Settings, Files);

        /// <summary>
        /// 账号会话状态（登录失效 / VIP 的计算与订阅入口）。**不要与上面那个播放会话
        /// <see cref="Session"/> 混淆**：这个是「登录态」，那个是「播放队列与进度」。
        ///
        /// 界面订阅它，而不是各自去问 <c>account/get</c>：登录失效时那个接口照样 200
        /// （只是 account 为 null），只有需登录接口回 301 才看得出来（见 spec/api/entitlement.md）。
        /// </summary>
        public static SessionMonitor AccountSession { get; } =
            new SessionMonitor(Http, Credentials, Settings, new AccountApi(Http));

        /// <summary>
        /// 登录 / 退出 / 会话状态变化后触发，**保证在 UI 线程上**（订阅方直接改界面）。
        /// </summary>
        public static event System.Action SessionChanged;

        static AppServices()
        {
            // SessionMonitor 的判定跑在后台线程上（Core 不假设有 UI 线程），这里统一封送回 Dispatcher。
            // 资料缓存先同步好再通知界面，订阅方（侧栏 / 设置页）读到的就是这一次判定的结果。
            AccountSession.StateChanged += _ =>
            {
                Cache.UserProfile = ProfileFromSession();
                RunOnUi(() => SessionChanged?.Invoke());
            };
        }

        /// <summary>
        /// 请求层是否持有 cookie —— 即「能不能带着登录态发请求」。会话失效时 cookie 已被清掉，
        /// 所以它同时是「还要不要去请求需登录的接口」的判据。要判断会话是否**有效**（能不能拿到
        /// 个性化推荐）用 <see cref="IsSessionValid"/>。
        /// </summary>
        public static bool IsLoggedIn() => !string.IsNullOrEmpty(Http.Cookie);

        /// <summary>会话已确认有效（<see cref="SessionState.Valid"/>）。<c>Unknown</c> 不算。</summary>
        public static bool IsSessionValid => AccountSession.IsValid;

        /// <summary>登录成功后统一入口：更新请求层与凭据存储，并立刻复核会话（拿到资料与 VIP）。</summary>
        public static async System.Threading.Tasks.Task SetCookieAsync(string cookie) =>
            await AccountSession.NoteLoggedInAsync(cookie);

        public static void SignOut()
        {
            // WebView2 的 cookie 不在这里清：它存在 WebView2 自己的用户数据目录里，而登录层关闭时
            // LoginPage / WebView2 已经从可视树上移除，这里拿不到实例。改由 LoginPage 在打开登录层时
            // 无条件 DeleteAllCookies —— 登录层只在需要登录时出现，旧 cookie 没有保留价值。
            //
            // 主动退出必须与「失效」区分：清掉「曾登录」标记、状态回 LoggedOut，
            // 否则界面会弹「登录已失效 · 重新登录」的横幅（见 SessionMonitor.NoteSignedOut）。
            AccountSession.NoteSignedOut();
            Cache.UserProfile = null;
            Cache.HomeDailySongs = null;
        }

        /// <summary>会话里的用户资料；会话不是 Valid 时为 null（此时界面显示「登录」入口）。</summary>
        private static UserProfile ProfileFromSession() =>
            AccountSession.State is SessionState.Valid valid
                ? new UserProfile(valid.UserId, valid.Nickname, valid.AvatarUrl)
                : null;

        /// <summary>Core 的判定在后台线程完成，界面更新必须回 UI 线程（否则 RPC_E_WRONG_THREAD）。</summary>
        private static void RunOnUi(System.Action action)
        {
            CoreDispatcher dispatcher;
            try
            {
                dispatcher = CoreApplication.MainView?.CoreWindow?.Dispatcher;
            }
            catch (System.Exception ex)
            {
                // 没有主视图（进程收尾 / 非 UI 宿主）：界面订阅者不该在后台线程被回调，放弃这次通知。
                App.WriteCrashLog(ex);
                return;
            }

            if (dispatcher == null)
            {
                return;
            }

            if (dispatcher.HasThreadAccess)
            {
                action();
                return;
            }

            _ = dispatcher.RunAsync(CoreDispatcherPriority.Normal, () => action());
        }

        private static NcmHttp CreateHttp()
        {
            var http = new NcmHttp(null, Xeapi);
            var cookie = Credentials.GetCookie();
            if (!string.IsNullOrEmpty(cookie))
            {
                http.Cookie = cookie;
            }

            return http;
        }
    }
}
