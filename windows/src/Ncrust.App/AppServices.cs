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

        /// <summary>登录 / 退出后触发（在调用 SetCookie / SignOut 的线程上，通常是 UI 线程）。</summary>
        public static event System.Action SessionChanged;

        public static bool IsLoggedIn() => !string.IsNullOrEmpty(Http.Cookie);

        /// <summary>登录成功后统一入口：更新请求层与凭据存储。</summary>
        public static void SetCookie(string cookie)
        {
            Http.Cookie = cookie;
            Credentials.SetCookie(cookie);
            SessionChanged?.Invoke();
        }

        public static void SignOut()
        {
            Http.Cookie = null;
            Credentials.ClearCookie();
            Cache.UserProfile = null;
            Cache.HomeDailySongs = null;
            SessionChanged?.Invoke();
        }

        private static NcmHttp CreateHttp()
        {
            var http = new NcmHttp();
            var cookie = Credentials.GetCookie();
            if (!string.IsNullOrEmpty(cookie))
            {
                http.Cookie = cookie;
            }

            return http;
        }
    }
}
