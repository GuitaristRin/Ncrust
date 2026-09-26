using System.Threading;
using System.Threading.Tasks;

namespace Ncrust.Core.Platform
{
    /// <summary>
    /// 从系统浏览器的 Cookie 库读出网易云会话 cookie（对应 Windows 端「唤起浏览器登录」
    /// 之后的导入步骤）。Windows 实现读 Chrome / Edge 的 Cookies 库并解密。
    ///
    /// 读不到（浏览器未安装、加密方式不支持、无权限）时返回 null，调用方退回二维码登录。
    /// 该能力在 UWP AppContainer 里的可行性由 windows/AGENTS.md 的 M0 #4 实测。
    /// </summary>
    public interface IBrowserCookieSource
    {
        /// <summary>返回 music.163.com 域的会话 cookie 串；读不到返回 null。</summary>
        Task<string?> GetMusicCookiesAsync(CancellationToken cancellationToken = default);
    }
}
