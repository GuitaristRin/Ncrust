using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;

namespace Ncrust.Core.Account
{
    /// <summary>
    /// 会话判定用到的两个网络调用。抽成接口是为了让 <see cref="SessionMonitor"/> 的单测能直接喂
    /// 夹具里的响应（真实现见 <see cref="Api.AccountApi"/>，它是这个接口的实现）。
    /// </summary>
    public interface ISessionApi
    {
        /// <summary><c>account/get</c> 的原始响应；null = 请求层失败（网络 / 非 2xx / 解析失败）。</summary>
        Task<JsonValue?> GetAccountAsync(CancellationToken cancellationToken = default);

        /// <summary><c>vip/info</c> 的原始响应；null = 失败（网络 / 非 200 / 301 未登录）。</summary>
        Task<JsonValue?> GetVipInfoAsync(long userId, CancellationToken cancellationToken = default);
    }
}
