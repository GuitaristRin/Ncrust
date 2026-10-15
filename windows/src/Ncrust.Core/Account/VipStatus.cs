using Ncrust.Core.Json;

namespace Ncrust.Core.Account
{
    /// <summary>有效期内的会员种类。优先级 SVIP &gt; 黑胶 VIP &gt; 音乐包。</summary>
    public enum VipKind
    {
        Svip,
        Vip,
        Package,
    }

    public static class VipKindExtensions
    {
        /// <summary>夹具 / 日志里的线格式。</summary>
        public static string Wire(this VipKind kind)
        {
            switch (kind)
            {
                case VipKind.Svip:
                    return "svip";
                case VipKind.Package:
                    return "package";
                default:
                    return "vip";
            }
        }
    }

    /// <summary>
    /// VIP 状态（对应 <c>weapi /api/music-vip-membership/client/vip/info</c>）。规格：<c>spec/api/entitlement.md</c> §VIP；
    /// 答案：<c>spec/fixtures/entitlement/vip.json</c>。
    ///
    /// 要点：接口失败（网络异常、非 200、301 未登录）一律 <see cref="Unknown"/>，
    /// **绝不能**显示成「未开通」—— 那会把「没查到」说成「你没买」。
    /// </summary>
    public abstract class VipStatus
    {
        private VipStatus()
        {
        }

        /// <summary>
        /// 派生 VIP 状态。<c>expireTime &gt; now</c> 即有效。
        /// <paramref name="localNowMs"/> 只在响应没带 <c>data.now</c> 时用：用户改过系统时间时，
        /// 用本机时间会把已过期的会员显示成有效（或反之）。
        /// </summary>
        public static VipStatus Derive(JsonValue? response, long localNowMs)
        {
            if (response == null || response.GetInt("code", -1) != 200)
            {
                return new Unknown();
            }

            var data = response.GetObject("data");
            if (data == null)
            {
                return new Unknown();
            }

            var now = data["now"] is { IsNumber: true } serverNow ? serverNow.LongValue : localNowMs;

            // 顺序即优先级：SVIP > 黑胶 VIP > 音乐包。
            var kinds = new[] { VipKind.Svip, VipKind.Vip, VipKind.Package };
            var entries = new[]
            {
                data.GetObject("redplus"),
                data.GetObject("associator"),
                data.GetObject("musicPackage"),
            };

            for (var i = 0; i < kinds.Length; i++)
            {
                var expire = ExpireTime(entries[i]);
                if (expire > now)
                {
                    return new Active(kinds[i], expire);
                }
            }

            // 都无效：只要有一项留下过到期时间，就是「开通过、已过期」，取最近的一项。
            long latest = 0;
            var latestKind = VipKind.Vip;
            for (var i = 0; i < kinds.Length; i++)
            {
                var expire = ExpireTime(entries[i]);
                if (expire > 0 && expire > latest)
                {
                    latest = expire;
                    latestKind = kinds[i];
                }
            }

            return latest > 0 ? (VipStatus)new Lapsed(latestKind, latest) : new None();
        }

        /// <summary>已开通。<paramref name="expireAt"/> 是毫秒时间戳。</summary>
        public sealed class Active : VipStatus
        {
            public Active(VipKind kind, long expireAt)
            {
                Kind = kind;
                ExpireAt = expireAt;
            }

            public VipKind Kind { get; }

            public long ExpireAt { get; }
        }

        /// <summary>开通过但已过期（都无效，但最近一项留下过到期时间）。</summary>
        public sealed class Lapsed : VipStatus
        {
            public Lapsed(VipKind kind, long expireAt)
            {
                Kind = kind;
                ExpireAt = expireAt;
            }

            public VipKind Kind { get; }

            public long ExpireAt { get; }
        }

        /// <summary>从未开通（三项都无效且无到期记录）。</summary>
        public sealed class None : VipStatus
        {
        }

        /// <summary>查不到（网络失败 / 非 200 / 未登录）。</summary>
        public sealed class Unknown : VipStatus
        {
        }

        private static long ExpireTime(JsonValue? entry) => entry == null ? 0 : entry.GetLong("expireTime");
    }
}
