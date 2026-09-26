using Ncrust.Core.Platform;
using Windows.Storage;

namespace Ncrust.Platform
{
    /// <summary>
    /// <see cref="ISettingsStore"/> 的 UWP 实现：<see cref="ApplicationData.LocalSettings"/>。
    /// 键名与 Android 的 <c>ncrust_settings</c> 相同。大块数据不要放这里（单值有大小上限），
    /// 用 <see cref="LocalFileStore"/>。
    /// </summary>
    public sealed class LocalSettingsStore : ISettingsStore
    {
        private static ApplicationDataContainer Settings => ApplicationData.Current.LocalSettings;

        public int GetInt(string key, int fallback) =>
            Settings.Values.TryGetValue(key, out var value) && value is int i ? i : fallback;

        public void SetInt(string key, int value) => Settings.Values[key] = value;

        public bool GetBool(string key, bool fallback) =>
            Settings.Values.TryGetValue(key, out var value) && value is bool b ? b : fallback;

        public void SetBool(string key, bool value) => Settings.Values[key] = value;

        public string GetString(string key) =>
            Settings.Values.TryGetValue(key, out var value) && value is string s ? s : null;

        public void SetString(string key, string value)
        {
            if (value == null)
            {
                Settings.Values.Remove(key);
            }
            else
            {
                Settings.Values[key] = value;
            }
        }
    }
}
