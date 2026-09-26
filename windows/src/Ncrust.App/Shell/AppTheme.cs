using System;
using Windows.UI;
using Windows.UI.ViewManagement;
using Windows.UI.Xaml;

namespace Ncrust.Shell
{
    /// <summary>主题模式（对应 Android ThemeMode）。持久化值与 Android 相同：SYSTEM / DARK / LIGHT。</summary>
    internal enum AppThemeMode
    {
        System,
        Dark,
        Light,
    }

    /// <summary>
    /// 明暗主题：设在根 Frame 的 RequestedTheme 上，所有页面继承（页面自身不再写死 RequestedTheme）；
    /// Kanesumi 的颜色 token 在 ThemeDictionaries 里按 Dark / Light 自动切换。同时同步系统标题栏按钮颜色。
    /// </summary>
    internal static class AppTheme
    {
        private const string Key = "theme_mode";

        public static event Action Changed;

        public static AppThemeMode Mode { get; private set; }

        /// <summary>当前实际是否深色（「跟随系统」时读系统背景色）。</summary>
        public static bool IsDark => Mode == AppThemeMode.Dark || (Mode == AppThemeMode.System && SystemIsDark());

        /// <summary>启动时（第一个页面之前）应用保存的模式。</summary>
        public static void ApplySaved(FrameworkElement root)
        {
            Mode = Parse(AppServices.Settings.GetString(Key));
            root.RequestedTheme = ToElementTheme(Mode);
        }

        public static void Set(AppThemeMode mode)
        {
            Mode = mode;
            AppServices.Settings.SetString(Key, mode.ToString().ToUpperInvariant());
            if (Window.Current.Content is FrameworkElement root)
            {
                root.RequestedTheme = ToElementTheme(mode);
            }

            ApplyTitleBarColors();
            Changed?.Invoke();
        }

        /// <summary>系统标题栏按钮（最小化 / 最大化 / 关闭）：透明底，前景随明暗。</summary>
        public static void ApplyTitleBarColors()
        {
            var dark = IsDark;
            var foreground = dark ? Colors.White : Colors.Black;
            var titleBar = ApplicationView.GetForCurrentView().TitleBar;
            titleBar.ButtonBackgroundColor = Colors.Transparent;
            titleBar.ButtonInactiveBackgroundColor = Colors.Transparent;
            titleBar.ButtonForegroundColor = foreground;
            titleBar.ButtonInactiveForegroundColor = Color.FromArgb(0xFF, 0x80, 0x80, 0x80);
            titleBar.ButtonHoverBackgroundColor = dark ? Color.FromArgb(0x22, 0xFF, 0xFF, 0xFF) : Color.FromArgb(0x14, 0x00, 0x00, 0x00);
            titleBar.ButtonHoverForegroundColor = foreground;
            titleBar.ButtonPressedBackgroundColor = dark ? Color.FromArgb(0x44, 0xFF, 0xFF, 0xFF) : Color.FromArgb(0x28, 0x00, 0x00, 0x00);
            titleBar.ButtonPressedForegroundColor = foreground;
        }

        private static AppThemeMode Parse(string value)
        {
            switch (value)
            {
                case "DARK":
                    return AppThemeMode.Dark;
                case "LIGHT":
                    return AppThemeMode.Light;
                default:
                    return AppThemeMode.System; // 与 Android 默认一致
            }
        }

        private static ElementTheme ToElementTheme(AppThemeMode mode)
        {
            switch (mode)
            {
                case AppThemeMode.Dark:
                    return ElementTheme.Dark;
                case AppThemeMode.Light:
                    return ElementTheme.Light;
                default:
                    return ElementTheme.Default;
            }
        }

        private static bool SystemIsDark()
        {
            try
            {
                var background = new UISettings().GetColorValue(UIColorType.Background);
                return background.R < 128;
            }
            catch
            {
                return true;
            }
        }
    }
}
