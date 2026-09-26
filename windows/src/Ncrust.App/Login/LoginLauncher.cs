using System;

namespace Ncrust.Login
{
    /// <summary>页面请求打开登录层（登录层由 Shell 承载）的静态入口。</summary>
    internal static class LoginLauncher
    {
        public static event Action LaunchRequested;

        public static void Request() => LaunchRequested?.Invoke();
    }
}
