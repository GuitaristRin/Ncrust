using System;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;

namespace Ncrust.Core.Api
{
    /// <summary>
    /// 账号与用户歌单（对应 Android <c>PlaylistApi</c> 的 account / user playlist 段）。
    /// </summary>
    public sealed class AccountApi
    {
        private const string AccountGetPath = "/eapi/w/nuser/account/get";
        private const string UserPlaylistPath = "/eapi/user/playlist";

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

        /// <summary>「我喜欢的音乐」歌单 id：优先 specialType != 0，其次按名字兜底。</summary>
        public async Task<long?> GetLikedPlaylistIdAsync(long uid, CancellationToken cancellationToken = default)
        {
            var playlists = await GetUserPlaylistsAsync(uid, 200, 0, cancellationToken).ConfigureAwait(false);
            foreach (var playlist in playlists)
            {
                if (playlist.SpecialType != 0)
                {
                    return playlist.Id;
                }
            }

            foreach (var playlist in playlists)
            {
                if (playlist.Name.Contains("我喜欢的音乐"))
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
    }
}
