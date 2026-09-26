namespace Ncrust.Core.Platform
{
    /// <summary>
    /// 小型设置项的键值存储（对应 Android 的 <c>ncrust_settings</c>）。
    /// 键名尽量与 Android 相同。大块数据（队列、音乐库、歌词缓存）不要放这里，
    /// 用 <see cref="IFileStore"/> —— Windows 端的实现是 LocalSettings，单个值有大小上限。
    /// </summary>
    public interface ISettingsStore
    {
        int GetInt(string key, int fallback);
        void SetInt(string key, int value);

        bool GetBool(string key, bool fallback);
        void SetBool(string key, bool value);

        string? GetString(string key);
        void SetString(string key, string? value);
    }
}
