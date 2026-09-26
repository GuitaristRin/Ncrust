using System;
using Windows.UI;
using Windows.UI.Core;
using Windows.UI.ViewManagement;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Media;

namespace Kanesumi.Xaml
{
    /// <summary>
    /// 强调色跟随 Windows（「设置 → 个性化 → 颜色」，Windows 10 / 11 都有）。
    ///
    /// 平台控件本来就读 SystemAccentColor，Kanesumi 不覆盖它；这里只把 Kanesumi 自己的
    /// <c>KPrimaryBrush</c> 同步成同一个颜色，并按亮度给 <c>KOnPrimaryBrush</c> 选黑或白字
    /// （浅色强调色上放白字会看不清）。用户改强调色时实时跟随。
    /// 读不到系统强调色时用内置的云杉 #1DB954（tokens.json accents 的默认项）。
    /// </summary>
    public static class KanesumiAccent
    {
        public static readonly Color Fallback = Color.FromArgb(0xFF, 0x1D, 0xB9, 0x54);

        // 必须持有引用：UISettings 被回收后 ColorValuesChanged 就不再触发。
        private static UISettings _settings;

        /// <summary>在 UI 线程上调用一次（应用启动时）。</summary>
        public static void FollowSystem(ResourceDictionary resources)
        {
            if (_settings != null)
            {
                return;
            }

            var dispatcher = Window.Current.Dispatcher;
            _settings = new UISettings();
            Apply(resources, ReadSystemAccent());

            // ColorValuesChanged 在后台线程触发，改画刷要回到 UI 线程。
            _settings.ColorValuesChanged += (sender, args) =>
            {
                var accent = ReadSystemAccent();
                _ = dispatcher.RunAsync(CoreDispatcherPriority.Normal, () => Apply(resources, accent));
            };
        }

        public static Color ReadSystemAccent()
        {
            try
            {
                return (_settings ?? new UISettings()).GetColorValue(UIColorType.Accent);
            }
            catch
            {
                return Fallback;
            }
        }

        public static void Apply(ResourceDictionary resources, Color accent)
        {
            if (Find(resources, "KPrimaryBrush") is SolidColorBrush primaryBrush)
            {
                primaryBrush.Color = accent;
            }

            if (Find(resources, "KOnPrimaryBrush") is SolidColorBrush onPrimaryBrush)
            {
                onPrimaryBrush.Color = IsLight(accent) ? Colors.Black : Colors.White;
            }
        }

        /// <summary>用索引器查找：它会查合并字典（Kanesumi 的画刷在合并进来的 Colors.xaml 里）。</summary>
        private static object Find(ResourceDictionary resources, string key)
        {
            try
            {
                return resources[key];
            }
            catch
            {
                return null;
            }
        }

        /// <summary>WCAG 相对亮度 &gt; 0.5 视为浅色（白字对比度不足 4.5 左右）。</summary>
        public static bool IsLight(Color color) => RelativeLuminance(color) > 0.5;

        private static double RelativeLuminance(Color color) =>
            0.2126 * Channel(color.R) + 0.7152 * Channel(color.G) + 0.0722 * Channel(color.B);

        private static double Channel(byte value)
        {
            var c = value / 255.0;
            return c <= 0.03928 ? c / 12.92 : Math.Pow((c + 0.055) / 1.055, 2.4);
        }
    }
}
