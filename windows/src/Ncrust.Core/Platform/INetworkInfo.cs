namespace Ncrust.Core.Platform
{
    /// <summary>
    /// 当前网络状态。Android 按 Wi-Fi / 移动网络区分，Windows 按是否计费区分：
    /// 计费网络使用「移动」档音质，webLog 上报的 <c>wifi</c> 字段取反值。
    /// </summary>
    public interface INetworkInfo
    {
        bool IsMetered { get; }
    }
}
