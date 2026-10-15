using Ncrust.Core.Json;

namespace Ncrust.Core.Account
{
    /// <summary>
    /// 会话状态（每进程一个 <see cref="SessionMonitor"/>，界面订阅）。规格：<c>spec/api/entitlement.md</c> §状态模型；
    /// 答案：<c>spec/fixtures/entitlement/session.json</c>。
    ///
    /// 关键区分：登录失效时 <c>account/get</c> **仍然 200**，只是 <c>account</c> / <c>profile</c> 为 null ——
    /// 只看 HTTP 状态码查不出来；而网络失败 / 非 200 必须维持上一个已知状态，
    /// 一次抖动就把用户判成失效是最糟的误报。
    /// </summary>
    public abstract class SessionState
    {
        private SessionState()
        {
        }

        /// <summary>
        /// 判定会话状态。
        ///
        /// <paramref name="hasCredential"/> = 本地是否持有凭证，**包含「曾登录」标记**：转入
        /// <see cref="Expired"/> 时会清掉 cookie 但保留该标记，之后必须一直判成 <see cref="Expired"/>
        /// （横幅据此说「登录已失效」而不是「未登录」）；<see cref="LoggedOut"/> 只留给从没登录过的设备。
        /// </summary>
        /// <param name="accountGet"><c>account/get</c> 的响应；null = 请求层失败（网络 / 非 2xx / 解析失败）。</param>
        /// <param name="otherApiCode">本次判定期间任一需登录接口返回的业务码（301 即未登录）。</param>
        public static SessionState Derive(bool hasCredential, JsonValue? accountGet, int? otherApiCode = null)
        {
            // 301 是服务端的明确答复，优先于任何其他信号。
            if (otherApiCode == 301)
            {
                return new Expired();
            }

            if (accountGet == null || accountGet.GetInt("code", -1) != 200)
            {
                return new Unknown();
            }

            var account = accountGet.GetObject("account");
            var profile = accountGet.GetObject("profile");
            if (account != null && profile != null)
            {
                var userId = profile.GetLong("userId");
                if (userId == 0)
                {
                    userId = account.GetLong("id");
                }

                return new Valid(
                    userId,
                    FirstNonEmpty(profile.GetString("nickname"), account.GetString("userName")),
                    FirstNonEmpty(profile.GetString("avatarUrl"), account.GetString("avatarUrl")),
                    new VipStatus.Unknown());
            }

            return hasCredential ? (SessionState)new Expired() : new LoggedOut();
        }

        /// <summary>正常登录。VIP 由 <see cref="SessionMonitor"/> 另行拉取，判定函数只负责填 <see cref="VipStatus.Unknown"/>。</summary>
        public sealed class Valid : SessionState
        {
            public Valid(long userId, string nickname, string avatarUrl, VipStatus vip)
            {
                UserId = userId;
                Nickname = nickname;
                AvatarUrl = avatarUrl;
                Vip = vip;
            }

            public long UserId { get; }

            public string Nickname { get; }

            public string AvatarUrl { get; }

            public VipStatus Vip { get; }

            /// <summary>拉到 VIP 状态后换一份（<see cref="Valid"/> 自身不可变）。</summary>
            public Valid WithVip(VipStatus vip) => new Valid(UserId, Nickname, AvatarUrl, vip);
        }

        /// <summary>本地既没有 cookie 也没有「曾登录」标记。</summary>
        public sealed class LoggedOut : SessionState
        {
        }

        /// <summary>有凭证但服务端不认（<c>account</c> 为空，或任一需登录接口返回 301）。</summary>
        public sealed class Expired : SessionState
        {
        }

        /// <summary>还没判定过 / 判不了（网络失败、非 200）。界面按「非 Valid」处理，但不显示失效横幅。</summary>
        public sealed class Unknown : SessionState
        {
        }

        /// <summary>状态类型的短名（<c>Valid</c> / <c>Expired</c> / <c>LoggedOut</c> / <c>Unknown</c>）：日志里只记类型，不记昵称等用户资料。</summary>
        public static string TypeName(SessionState state)
        {
            if (state is Valid)
            {
                return "Valid";
            }

            if (state is Expired)
            {
                return "Expired";
            }

            return state is LoggedOut ? "LoggedOut" : "Unknown";
        }

        private static string FirstNonEmpty(params string?[] values)
        {
            foreach (var value in values)
            {
                if (!string.IsNullOrEmpty(value))
                {
                    return value!;
                }
            }

            return string.Empty;
        }
    }
}
