using System.Globalization;
using Windows.UI.Xaml;

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
    }
}
