using System.Collections.Generic;

namespace Ncrust.Core.Net
{
    /// <summary>
    /// xeapi（Android 身份）的自洽指纹：UA、<c>x-appver</c>、cookie <c>appver</c>、公钥请求的
    /// <c>appVersion</c> 同源，<c>x-osver</c> / cookie <c>osver</c> 与 UA 里的系统版本一致。
    ///
    /// 为什么必须同源：官方 9.6.22 的 UA 由同一版本号现拼（<c>y22/b.java</c>），<c>x-osver</c>
    /// 取真实系统（<c>icustomconfig/j0.java</c>）。1.0.3 及更早的 UA 写 9.5.61 + Android 12、
    /// <c>x-appver</c> 却是 9.1.65、<c>x-osver</c> 却是 16，真实客户端不可能产出这种组合
    /// （参 spec/api/xeapi.md §3.3；Android 对应 <c>XeapiIdentity.kt</c>）。
    ///
    /// 与 Android 的差异：Android 取设备真值，Windows 没有 Android 设备可取，于是固定为 UA 里
    /// 那台机型（Android 12 / HBN-AL00）。设备 cookie 只发能从 UA 自洽推出的
    /// <c>mobilename</c> 与 <c>versioncode</c>；<c>brand</c>、<c>resolution</c> 没有真值，臆造等于
    /// 制造新的不一致，故不发。
    /// </summary>
    public static class XeapiIdentity
    {
        /// <summary>唯一版本来源：UA、<c>x-appver</c>、cookie <c>appver</c>、公钥请求 <c>appVersion</c>。</summary>
        public const string AppVersion = "9.5.61";

        /// <summary>官方版本串的构建后缀与 build 号，观测自 9.5.61 真机 UA（spec §3.3）。</summary>
        public const string AppVersionSuffix = "260802021928";

        public const string BuildNo = "9005061";

        /// <summary>与 UA 里的 <c>Android 12</c> 一致。</summary>
        public const string OsVersion = "12";

        public const string Model = "HBN-AL00";

        public const string UserAgent =
            "NeteaseMusic/" + AppVersion + "." + AppVersionSuffix + "(" + BuildNo + ");" +
            "Dalvik/2.1.0 (Linux; U; Android " + OsVersion + "; " + Model + " Build/cd737a2.0)";

        /// <summary>官方 <c>AbsCookieStore.initDevicesCookie</c> 中可自洽给出的设备 cookie。</summary>
        public static IReadOnlyList<KeyValuePair<string, string>> DeviceCookies { get; } = new[]
        {
            new KeyValuePair<string, string>("mobilename", Model),
            new KeyValuePair<string, string>("versioncode", BuildNo),
        };
    }
}
