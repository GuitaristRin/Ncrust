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
    }

    /// <summary>模板里 x:Bind 函数绑定用的显示格式。界面文案集中在这里，i18n 落地时替换。</summary>
    public static class DisplayFormat
    {
        public static string ArtistCounts(int albumSize, int musicSize) =>
            string.Format(CultureInfo.InvariantCulture, "{0} 张专辑 · {1} 首歌曲", albumSize, musicSize);

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
