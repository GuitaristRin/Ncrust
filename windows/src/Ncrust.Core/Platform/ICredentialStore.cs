namespace Ncrust.Core.Platform
{
    /// <summary>
    /// 登录 cookie 的存储（对应 Android 的 <c>CookieManager</c>）。
    /// Android 端是明文 SharedPreferences；Windows 端用 PasswordVault，
    /// 存不下完整 cookie 时改用 DataProtectionProvider 加密后写文件（M0 验证）。
    /// </summary>
    public interface ICredentialStore
    {
        /// <summary>未登录时返回 null。</summary>
        string? GetCookie();

        void SetCookie(string cookie);

        void ClearCookie();
    }
}
