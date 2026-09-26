namespace Ncrust.Core.Api
{
    /// <summary>
    /// 网易图床缩略参数（对应 Android <c>network/CoverUrls.kt</c>）。图床支持 <c>?param=WxH</c> 服务端缩略，
    /// 请求小图能让传输与解码像素同时减少数倍。URL 已带 param（官方认为的最佳尺寸）时原样保留，绝不改动。
    /// </summary>
    public static class CoverUrls
    {
        /// <summary>列表行 / 网格磁贴封面。</summary>
        public const int SmallPx = 640;

        /// <summary>播放器大封面 / 详情页头图。</summary>
        public const int LargePx = 1080;

        public static string? Small(string? url) => WithSize(url, SmallPx);

        public static string? Large(string? url) => WithSize(url, LargePx);

        /// <summary>空串返回 null（调用方据此显示空封面，而不是把空串交给图片控件）。</summary>
        public static string? WithSize(string? url, int px)
        {
            if (string.IsNullOrWhiteSpace(url))
            {
                return null;
            }

            if (!url!.StartsWith("http"))
            {
                return url;
            }

            if (url.Contains("?param=") || url.Contains("&param="))
            {
                return url;
            }

            var separator = url.Contains("?") ? "&" : "?";
            return url + separator + "param=" + px + "y" + px;
        }
    }
}
