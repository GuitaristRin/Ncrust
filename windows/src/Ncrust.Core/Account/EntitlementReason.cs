namespace Ncrust.Core.Account
{
    /// <summary>
    /// 一条播放限制的原因。规格：<c>spec/api/entitlement.md</c>。
    ///
    /// <c>trial</c> / <c>capped</c> 只会取 <see cref="Vip"/> / <see cref="Album"/> / <see cref="Login"/>，
    /// <c>unavailable</c> 取全集（含 <see cref="Copyright"/> / <see cref="Unknown"/>）——
    /// 各分支的合法取值由判定函数保证，界面只写一处「原因 → 文案」映射。
    /// </summary>
    public enum EntitlementReason
    {
        /// <summary>需要黑胶 VIP（或音乐包）。</summary>
        Vip,

        /// <summary>需购买数字专辑（<c>fee == 4</c>）。</summary>
        Album,

        /// <summary>当前会话不是 <see cref="SessionState.Valid"/>：登录后可能就能播 / 拿到更高音质。</summary>
        Login,

        /// <summary>无版权 / 已下架（歌曲详情 <c>privilege.st &lt; 0</c>）。</summary>
        Copyright,

        /// <summary>判不出来。**不编原因**：宁可说「暂时无法播放」。</summary>
        Unknown,
    }

    public static class EntitlementReasonExtensions
    {
        /// <summary>夹具 / 日志里的线格式（与 Android <c>EntitlementReason.wire</c> 一致）。</summary>
        public static string Wire(this EntitlementReason reason)
        {
            switch (reason)
            {
                case EntitlementReason.Vip:
                    return "vip";
                case EntitlementReason.Album:
                    return "album";
                case EntitlementReason.Login:
                    return "login";
                case EntitlementReason.Copyright:
                    return "copyright";
                default:
                    return "unknown";
            }
        }

        /// <summary>
        /// 线格式 → 枚举；未知字符串返回 null。逐条 switch 而不用 <c>Enum.GetValues</c>：
        /// .NET Native 下枚举反射受限，这个仓库一贯避开反射（见 windows/AGENTS.md）。
        /// </summary>
        public static EntitlementReason? FromWire(string? value)
        {
            switch (value)
            {
                case "vip":
                    return EntitlementReason.Vip;
                case "album":
                    return EntitlementReason.Album;
                case "login":
                    return EntitlementReason.Login;
                case "copyright":
                    return EntitlementReason.Copyright;
                case "unknown":
                    return EntitlementReason.Unknown;
                default:
                    return null;
            }
        }
    }
}
