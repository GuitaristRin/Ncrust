using System;
using System.Globalization;
using Ncrust.Core.Api;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Media;
using Windows.UI.Xaml.Media.Imaging;

namespace Ncrust.Resources
{
    /// <summary>共用列表项模板（x:Bind 需要代码隐藏类）。在 App.xaml 中合并。</summary>
    public sealed partial class Templates : ResourceDictionary
    {
        public Templates()
        {
            InitializeComponent();
        }

        /// <summary>搜索记录行尾的删除键：DataContext 是那条记录。</summary>
        private void SuggestionDeleteClick(object sender, RoutedEventArgs e)
        {
            if ((sender as FrameworkElement)?.DataContext is Shell.SearchSuggestion suggestion)
            {
                Shell.SearchSuggestion.RequestRemove(suggestion);
            }
        }

        /// <summary>队列行尾的 ✕：Tag 是该行的队列索引。</summary>
        private void QueueRemoveClick(object sender, RoutedEventArgs e)
        {
            if ((sender as FrameworkElement)?.Tag is int index)
            {
                Player.QueuePresenter.RemoveAt(index);
            }
        }
    }

    /// <summary>模板里 x:Bind 函数绑定用的显示格式。界面文案集中在这里，i18n 落地时替换。</summary>
    public static class DisplayFormat
    {
        public static string ArtistCounts(int albumSize, int musicSize) =>
            string.Format(CultureInfo.InvariantCulture, "{0} 张专辑 · {1} 首歌曲", albumSize, musicSize);

        /// <summary>7 档音质的显示名，下标即偏好索引（与 Android qualityOptions、QualityLadder.ApiLevels 一一对应）。</summary>
        public static readonly string[] QualityLabels = { "压缩", "较好", "更好", "无损", "高解析", "高清环绕声", "杜比全景声" };

        /// <summary>档位 API 名 → 显示名（如 lossless → 无损）；未知档位返回空串。</summary>
        public static string QualityLabel(string level)
        {
            for (var i = 0; i < Core.Playback.QualityLadder.ApiLevels.Count; i++)
            {
                if (Core.Playback.QualityLadder.ApiLevels[i] == level)
                {
                    return QualityLabels[i];
                }
            }

            return string.Empty;
        }

        /// <summary>「12 首」（对应 Android trackCount）。</summary>
        public static string TrackCount(int count) => count.ToString(CultureInfo.InvariantCulture) + " 首";

        /// <summary>「12 首歌曲」（对应 Android trackCountSongs）。</summary>
        public static string SongCount(int count) => count.ToString(CultureInfo.InvariantCulture) + " 首歌曲";

        /// <summary>发行年份；未知（0）为空串。</summary>
        public static string Year(long publishTimeMs) =>
            publishTimeMs > 0 ? FromEpoch(publishTimeMs).Year.ToString(CultureInfo.InvariantCulture) : string.Empty;

        /// <summary>发行日期 yyyy-MM-dd；未知为空串。</summary>
        public static string Date(long publishTimeMs) =>
            publishTimeMs > 0 ? FromEpoch(publishTimeMs).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) : string.Empty;

        /// <summary>歌手页专辑磁贴：「2020 · 12 首」（对应 Android ArtistAlbumGridItem）。</summary>
        public static string AlbumYearAndCount(long publishTimeMs, int size)
        {
            var year = Year(publishTimeMs);
            return year.Length == 0 ? TrackCount(size) : year + " · " + TrackCount(size);
        }

        /// <summary>音乐库专辑磁贴：「歌手 · 12 首」（对应 Android albumArtistAndCount）。</summary>
        public static string ArtistAndCount(string artist, int count) =>
            string.IsNullOrEmpty(artist) ? TrackCount(count) : artist + " · " + TrackCount(count);

        /// <summary>用「 · 」连接非空的几段。</summary>
        public static string Join(params string[] parts)
        {
            var result = string.Empty;
            foreach (var part in parts)
            {
                if (string.IsNullOrEmpty(part))
                {
                    continue;
                }

                result = result.Length == 0 ? part : result + " · " + part;
            }

            return result;
        }

        // ── 账号权益（试听 / 音质受限 / 登录失效 / VIP 状态）：用词与 Android i18n 一致 ──────────
        // 固定文案（失效横幅、每日推荐说明）直接写在对应 XAML 里；这里只放按数据拼出来的那些。

        /// <summary>音质徽标位置的试听标记。</summary>
        public const string TrialBadge = "试听";

        /// <summary>播放权益原因文案（跳过 / 不可播用它：login 说「登录后可播放」）。</summary>
        public static string PlaybackReason(Core.Account.EntitlementReason reason)
        {
            switch (reason)
            {
                case Core.Account.EntitlementReason.Vip:
                    return "需要黑胶 VIP";
                case Core.Account.EntitlementReason.Album:
                    return "需购买专辑";
                case Core.Account.EntitlementReason.Copyright:
                    return "暂无版权";
                case Core.Account.EntitlementReason.Login:
                    return "登录后可播放";
                default:
                    return "暂时无法播放";
            }
        }

        /// <summary>试听提示里的原因：登录失效时是「登录后**可能**可完整播放」，与跳过文案不同。</summary>
        public static string TrialReason(Core.Account.EntitlementReason reason) =>
            reason == Core.Account.EntitlementReason.Login ? "登录后可能可完整播放" : PlaybackReason(reason);

        /// <summary>「试听 45 秒 · 需要黑胶 VIP」。</summary>
        public static string TrialNotice(int seconds, Core.Account.EntitlementReason reason) =>
            string.Format(CultureInfo.InvariantCulture, "试听 {0} 秒 · {1}", seconds, TrialReason(reason));

        /// <summary>「已跳过《歌名》：暂无版权」。</summary>
        public static string SkippedSong(string name, Core.Account.EntitlementReason reason) =>
            "已跳过《" + name + "》：" + PlaybackReason(reason);

        /// <summary>连续多首不可播时合并成一条。</summary>
        public static string SkippedSongs(int count) =>
            string.Format(CultureInfo.InvariantCulture, "已跳过 {0} 首无法播放的歌曲", count);

        /// <summary>「当前账号最高可播放「极高」」/「登录后可获得更高音质」。</summary>
        public static string QualityCapped(string level, Core.Account.EntitlementReason reason)
        {
            if (reason == Core.Account.EntitlementReason.Login)
            {
                return "登录后可获得更高音质";
            }

            var label = QualityLabel(level);
            return "当前账号最高可播放「" + (label.Length == 0 ? level : label) + "」";
        }

        /// <summary>会员状态（设置页账户区）。接口查不到时说「未知」，绝不显示成「未开通」。</summary>
        public static string VipLabel(Core.Account.VipStatus vip)
        {
            if (vip is Core.Account.VipStatus.Active active)
            {
                return VipKindLabel(active.Kind) + " · 有效期至 " + VipDate(active.ExpireAt);
            }

            if (vip is Core.Account.VipStatus.Lapsed lapsed)
            {
                return VipKindLabel(lapsed.Kind) + " 已于 " + VipDate(lapsed.ExpireAt) + " 过期 · VIP 歌曲仅可试听";
            }

            if (vip is Core.Account.VipStatus.None)
            {
                return "未开通黑胶 VIP · VIP 歌曲仅可试听，最高音质受限";
            }

            return "会员状态未知";
        }

        private static string VipKindLabel(Core.Account.VipKind kind)
        {
            switch (kind)
            {
                case Core.Account.VipKind.Svip:
                    return "黑胶 SVIP";
                case Core.Account.VipKind.Package:
                    return "音乐包";
                default:
                    return "黑胶 VIP";
            }
        }

        private static string VipDate(long expireAtMs) =>
            expireAtMs > 0 ? FromEpoch(expireAtMs).ToString("yyyy-MM-dd", CultureInfo.InvariantCulture) : "—";

        private static DateTimeOffset FromEpoch(long ms) => DateTimeOffset.FromUnixTimeMilliseconds(ms).ToLocalTime();

        /// <summary>
        /// 列表 / 磁贴封面。**图片一律经这里绑定，不要把字符串直接 x:Bind 到 Image.Source**：
        /// 没有封面的专辑 / 歌手 URL 是空串，空串转 ImageSource 会抛 ArgumentException
        /// （「The value cannot be converted to type ImageSource」）并带崩进程。
        /// 这里空串返回 null（显示空白封面）；有 URL 时请求服务端缩略图，并按显示尺寸解码以省内存。
        /// </summary>
        /// <param name="url">原始封面 URL。</param>
        /// <param name="decodePx">解码宽度（像素，约为显示尺寸的 2 倍以兼顾高 DPI）。</param>
        public static ImageSource Cover(string url, int decodePx)
        {
            var sized = CoverUrls.Small(url);
            if (sized == null || !Uri.TryCreate(sized, UriKind.Absolute, out var uri))
            {
                return null;
            }

            return new BitmapImage(uri) { DecodePixelWidth = decodePx, DecodePixelType = DecodePixelType.Logical };
        }
    }
}
