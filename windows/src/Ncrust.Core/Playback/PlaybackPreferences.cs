using System;
using Ncrust.Core.Platform;

namespace Ncrust.Core.Playback
{
    /// <summary>
    /// 播放相关设置（对应 Android 的 <c>ncrust_settings</c> 键）。键名与 Android 相同。
    /// 默认：gapless 开、歌词翻译开、Wi-Fi 无损(3) / 移动较好(1)。
    /// </summary>
    public sealed class PlaybackPreferences
    {
        public const int DefaultWifiQualityIndex = 3;
        public const int DefaultMobileQualityIndex = 1;

        private readonly ISettingsStore _settings;

        public PlaybackPreferences(ISettingsStore settings) =>
            _settings = settings ?? throw new ArgumentNullException(nameof(settings));

        public bool GaplessEnabled
        {
            get => _settings.GetBool("gapless_playback", true);
            set => _settings.SetBool("gapless_playback", value);
        }

        public bool LyricsTranslation
        {
            get => _settings.GetBool("lyrics_translation", true);
            set => _settings.SetBool("lyrics_translation", value);
        }

        public int WifiQualityIndex
        {
            get => _settings.GetInt("wifi_quality", DefaultWifiQualityIndex);
            set => _settings.SetInt("wifi_quality", value);
        }

        public int MobileQualityIndex
        {
            get => _settings.GetInt("mobile_quality", DefaultMobileQualityIndex);
            set => _settings.SetInt("mobile_quality", value);
        }

        /// <summary>按网络是否计费选档位：计费走「移动」档，否则走 Wi-Fi 档。</summary>
        public string QualityForNetwork(bool metered) =>
            QualityLadder.LevelForPreferenceIndex(metered ? MobileQualityIndex : WifiQualityIndex);
    }
}
