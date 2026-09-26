namespace Ncrust.Core.Platform
{
    /// <summary>
    /// 解码能力探测，供音质阶梯的 FLAC 门控使用（对应 Android <c>SongUrlFetcher</c> 里的 MediaCodec 检查）。
    /// Windows 10 1809 起系统自带 FLAC 解码器，实现恒返回 true；保留接口是为了让 Core 的
    /// 阶梯逻辑与 <c>spec/fixtures/quality</c> 保持同一套分支。
    /// </summary>
    public interface ICodecProbe
    {
        /// <summary>为 false 时跳过 lossless / hires / jyeffect。</summary>
        bool SupportsFlac { get; }
    }
}
