using Ncrust.Core.Platform;

namespace Ncrust.Platform
{
    /// <summary>
    /// <see cref="ICodecProbe"/> 的 UWP 实现：最低支持的 Windows 10 1809（17763）已内置 FLAC
    /// 解码器，恒为 true。保留接口是为了让 Core 的阶梯逻辑与 <c>spec/fixtures/quality</c> 一致。
    /// </summary>
    public sealed class WindowsCodecProbe : ICodecProbe
    {
        public bool SupportsFlac => true;
    }
}
