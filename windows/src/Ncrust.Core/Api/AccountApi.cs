using System;
using System.Collections.Generic;
using System.Globalization;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Account;
using Ncrust.Core.Auth;
using Ncrust.Core.Json;
using Ncrust.Core.Net;

namespace Ncrust.Core.Api
{
    /// <summary>
    /// 账号与用户歌单（对应 Android <c>PlaylistApi</c> 的 account / user playlist 段）。
    /// 同时是会话监视器的网络来源（<see cref="ISessionApi"/>）：它会就地消化 301。
    /// </summary>
    public sealed class AccountApi : ISessionApi
    {
        private const string AccountGetPath = "/eapi/w/nuser/account/get";
        private const string UserPlaylistPath = "/eapi/user/playlist";

        /// <summary>会员状态（weapi）。规格：<c>spec/api/entitlement.md</c> §VIP。</summary>
        private const string VipInfoPath = "/api/music-vip-membership/client/vip/info";

        private readonly NcmHttp _http;

        public AccountApi(NcmHttp http)
        {
            _http = http ?? throw new ArgumentNullException(nameof(http));
        }

        /// <summary>当前登录用户资料；未登录返回 null。</summary>
        public async Task<UserProfile?> GetProfileAsync(CancellationToken cancellationToken = default)
        {
            using (var response = await _http
                .EapiPostAsync(AccountGetPath, null, useInterface: false, cancellationToken)
                .ConfigureAwait(false))
            {
                if (!response.IsSuccessStatusCode)
                {
                    // HTTP 层就失败了（网关 / 5xx）：这不是「未登录」。调用方把 null 当作会话失效，
                    // 所以这里必须抛出去，别让一次服务端抖动把用户登出。
                    throw new NcmApiException($"account/get http {(int)response.StatusCode}");
                }

                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                var json = JsonValue.Parse(body);
                var account = json.GetObject("account");
                var profile = json.GetObject("profile");
                if (account == null && profile == null)
                {
                    return null;
                }

                var userId = profile?.GetLong("userId") ?? 0;
                if (userId == 0)
                {
                    userId = account?.GetLong("id") ?? 0;
                }

                var nickname = FirstNonEmpty(
                    profile?.GetString("nickname"),
                    account?.GetString("userName"),
                    "用户");
                var avatar = FirstNonEmpty(profile?.GetString("avatarUrl"), account?.GetString("avatarUrl"));
                return new UserProfile(userId, nickname, avatar);
            }
        }

        public async Task<long> GetCurrentUserIdAsync(CancellationToken cancellationToken = default) =>
            (await GetProfileAsync(cancellationToken).ConfigureAwait(false))?.UserId ?? 0;

        /// <summary>
        /// <c>account/get</c> 的原始响应，供会话判定用。判定的依据不只是「有没有资料」，
        /// 还要看业务码与 <c>account</c>/<c>profile</c> 的空值组合（见 <see cref="SessionState.Derive"/>），
        /// 所以这里不解析成 <see cref="UserProfile"/>，直接交出 JSON。
        ///
        /// 与 <see cref="GetProfileAsync"/> 的区别：请求层失败返回 null 而不是抛异常——调用方
        /// （会话监视器）需要把「网络失败」与「未登录」分开处理。
        /// </summary>
        public async Task<JsonValue?> GetAccountAsync(CancellationToken cancellationToken = default)
        {
            try
            {
                using (var response = await _http
                    .EapiPostAsync(AccountGetPath, null, useInterface: false, cancellationToken)
                    .ConfigureAwait(false))
                {
                    if (!response.IsSuccessStatusCode)
                    {
                        return null; // 网关 / 5xx：判不了，不是「未登录」。
                    }

                    var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                    return ReadBusinessJson(body);
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception)
            {
                return null;
            }
        }

        /// <summary>
        /// 黑胶 VIP / 音乐包状态。只应在会话 <see cref="SessionState.Valid"/> 时调用。
        /// 失败（网络异常 / 非 200 / 301）返回 null —— 调用方显示为「未知」，**绝不**显示成「未开通」。
        /// </summary>
        public async Task<JsonValue?> GetVipInfoAsync(long userId, CancellationToken cancellationToken = default)
        {
            try
            {
                var payload = JsonText.Object(new[]
                {
                    new KeyValuePair<string, string>("userId", userId.ToString(CultureInfo.InvariantCulture)),
                });
                using (var response = await _http
                    .WeapiPostAsync(VipInfoPath, payload, null, cancellationToken)
                    .ConfigureAwait(false))
                {
                    if (!response.IsSuccessStatusCode)
                    {
                        return null;
                    }

                    var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                    return ReadBusinessJson(body);
                }
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception)
            {
                return null;
            }
        }

        /// <summary>
        /// 判定一段 cookie 是否仍能登录：账户接口返回的 <c>account</c> / <c>profile</c> 非空才算有效。
        ///
        /// 刻意做成静态、并且自己建一个一次性的 <see cref="NcmHttp"/>：调用它的场景（登录层刚读到
        /// cookie）里，这段 cookie 还没被确认，写进全局请求层会污染其它请求。
        ///
        /// 三态：没有非空 <c>MUSIC_U</c> 与「接口明确说未登录」是 <see cref="SessionValidation.Invalid"/>；
        /// 断网 / 超时 / 非 2xx / 响应不是 JSON 一律 <see cref="SessionValidation.Unknown"/> ——
        /// 不能当成失效，否则一次网络抖动就会把用户登出。
        /// </summary>
        /// <param name="cookie">待判定的会话 cookie 串。</param>
        /// <param name="handler">仅测试注入：给了就用它建临时请求层，不打真网络（同 <see cref="NcmHttp"/> 的构造签名）。</param>
        public static async Task<SessionValidation> ValidateSessionAsync(
            string? cookie,
            HttpMessageHandler? handler = null,
            CancellationToken cancellationToken = default)
        {
            if (!SessionCookie.Parse(cookie).HasMusicU)
            {
                return SessionValidation.Invalid; // 没有 MUSIC_U，或只有删除写法 `MUSIC_U=`：不必打接口。
            }

            using (var http = handler == null ? new NcmHttp() : new NcmHttp(handler))
            {
                http.Cookie = cookie;
                try
                {
                    using (var response = await http
                        .EapiPostAsync(AccountGetPath, null, useInterface: false, cancellationToken)
                        .ConfigureAwait(false))
                    {
                        if (!response.IsSuccessStatusCode)
                        {
                            return SessionValidation.Unknown;
                        }

                        var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                        var json = JsonValue.Parse(body);
                        return json.GetObject("account") != null || json.GetObject("profile") != null
                            ? SessionValidation.Valid
                            : SessionValidation.Invalid;
                    }
                }
                catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
                {
                    throw; // 调用方主动取消，别吞掉。
                }
                catch (Exception)
                {
                    return SessionValidation.Unknown;
                }
            }
        }

        /// <summary>用户歌单（含特殊歌单，<c>SpecialType != 0</c> 的需要调用方自行区分）。</summary>
        public async Task<IReadOnlyList<UserPlaylistInfo>> GetUserPlaylistsAsync(
            long uid,
            int limit = 100,
            int offset = 0,
            CancellationToken cancellationToken = default)
        {
            using (var response = await _http.EapiPostAsync(UserPlaylistPath, new[]
            {
                new KeyValuePair<string, string>("uid", uid.ToString()),
                new KeyValuePair<string, string>("limit", limit.ToString()),
                new KeyValuePair<string, string>("offset", offset.ToString()),
                new KeyValuePair<string, string>("includeVideo", "false"),
            }, useInterface: false, cancellationToken).ConfigureAwait(false))
            {
                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                var json = JsonValue.Parse(body);
                var code = json.GetInt("code", -1);
                if (code != 200)
                {
                    throw new NcmApiException($"user playlist code={code}");
                }

                var playlists = json.GetArray("playlist");
                if (playlists == null)
                {
                    return Array.Empty<UserPlaylistInfo>();
                }

                var result = new List<UserPlaylistInfo>(playlists.Count);
                foreach (var playlist in playlists)
                {
                    result.Add(UserPlaylistInfo.FromJson(playlist));
                }

                return result;
            }
        }

        /// <summary>
        /// 「我喜欢的音乐」（红心歌单）的 playlist id。必须精确命中它——旧实现取「第一个
        /// <c>specialType != 0</c>」，账号里若有其它特殊歌单会读到错误的歌单，导致收藏页读到的
        /// 单曲集合与 like 写入的目标不一致，表现为「收藏后隔天消失」。依次按名称、<c>specialType == 5</c>、
        /// 任意特殊歌单三级兜底。
        /// </summary>
        public async Task<long?> GetLikedPlaylistIdAsync(long uid, CancellationToken cancellationToken = default)
        {
            var playlists = await GetUserPlaylistsAsync(uid, 200, 0, cancellationToken).ConfigureAwait(false);

            // 名称最可靠：「我喜欢的音乐」不可被用户改名。
            foreach (var playlist in playlists)
            {
                if (playlist.Name.Contains("我喜欢的音乐") || playlist.Name.Contains("喜欢的音乐"))
                {
                    return playlist.Id;
                }
            }

            foreach (var playlist in playlists)
            {
                if (playlist.SpecialType == 5)
                {
                    return playlist.Id;
                }
            }

            // 最后兜底：任意特殊歌单（个别老账号 specialType 取值不同）。
            foreach (var playlist in playlists)
            {
                if (playlist.SpecialType != 0)
                {
                    return playlist.Id;
                }
            }

            return null;
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

        /// <summary>解析响应体并把业务码交给请求层（301 → 会话监视器立刻转 Expired）。解析失败返回 null。</summary>
        private JsonValue? ReadBusinessJson(string body)
        {
            JsonValue json;
            try
            {
                json = JsonValue.Parse(body);
            }
            catch (JsonParseException)
            {
                return null;
            }

            _http.ReportBusinessCode(json.GetInt("code", -1));
            return json;
        }
    }
}
