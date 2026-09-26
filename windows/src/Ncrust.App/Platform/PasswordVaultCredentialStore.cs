using Ncrust.Core.Platform;
using Windows.Security.Credentials;

namespace Ncrust.Platform
{
    /// <summary>
    /// <see cref="ICredentialStore"/> 的 UWP 实现：<see cref="PasswordVault"/>。
    /// **M0 #4 实测**：PasswordVault 单个密码有长度上限，完整网易 cookie 可能超限；
    /// 存不下就改用 <c>DataProtectionProvider</c> 加密后写文件。
    /// </summary>
    public sealed class PasswordVaultCredentialStore : ICredentialStore
    {
        private const string Resource = "Ncrust";
        private const string UserName = "cookie";

        public string GetCookie()
        {
            try
            {
                var credential = new PasswordVault().Retrieve(Resource, UserName);
                credential.RetrievePassword();
                return credential.Password;
            }
            catch
            {
                return null;
            }
        }

        public void SetCookie(string cookie)
        {
            var vault = new PasswordVault();
            Clear(vault);
            vault.Add(new PasswordCredential(Resource, UserName, cookie));
        }

        public void ClearCookie() => Clear(new PasswordVault());

        private static void Clear(PasswordVault vault)
        {
            try
            {
                foreach (var credential in vault.FindAllByResource(Resource))
                {
                    vault.Remove(credential);
                }
            }
            catch
            {
                // 没有已存条目时 FindAllByResource 会抛，忽略。
            }
        }
    }
}
