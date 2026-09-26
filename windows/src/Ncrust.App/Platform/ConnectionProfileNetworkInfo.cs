using Ncrust.Core.Platform;
using Windows.Networking.Connectivity;

namespace Ncrust.Platform
{
    /// <summary>
    /// <see cref="INetworkInfo"/> 的 UWP 实现：按 <c>ConnectionProfile.GetConnectionCost()</c>
    /// 是否 Unrestricted 判断「计费网络」。计费网络用「移动」档音质，webLog 的 wifi 字段取反。
    /// </summary>
    public sealed class ConnectionProfileNetworkInfo : INetworkInfo
    {
        public bool IsMetered
        {
            get
            {
                try
                {
                    var cost = NetworkInformation.GetInternetConnectionProfile()?.GetConnectionCost();
                    return cost != null && cost.NetworkCostType != NetworkCostType.Unrestricted;
                }
                catch
                {
                    return false;
                }
            }
        }
    }
}
