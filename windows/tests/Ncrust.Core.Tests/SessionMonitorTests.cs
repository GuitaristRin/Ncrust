using Ncrust.Core.Account;
using Ncrust.Core.Json;
using Ncrust.Core.Net;
using Ncrust.Core.Platform;
using Xunit;

namespace Ncrust.Core.Tests;

/// <summary>
/// 会话监视器：判定时机、301 就地转失效、清 cookie 但保留「曾登录」标记、网络失败维持原状态。
/// 判定函数本身的规则由夹具测试覆盖（EntitlementFixtureTests），这里只管接线。
/// </summary>
public class SessionMonitorTests
{
    private const string AccountValid =
        "{\"code\":200,\"account\":{\"id\":1,\"userName\":\"a\"},\"profile\":{\"userId\":1,\"nickname\":\"n\"}}";

    private const string AccountNull = "{\"code\":200,\"account\":null,\"profile\":null}";

    private const string VipActive =
        "{\"code\":200,\"data\":{\"now\":1000,\"associator\":{\"vipCode\":100,\"expireTime\":2000}}}";

    [Fact]
    public async Task NoCredential_IsLoggedOut_WithoutNetworkCall()
    {
        var api = new FakeApi();
        var monitor = NewMonitor(api, cookie: null);

        var state = await monitor.RefreshAsync(force: true);

        Assert.IsType<SessionState.LoggedOut>(state);
        Assert.Equal(0, api.AccountCalls);
    }

    [Fact]
    public async Task Valid_CarriesProfileAndVip()
    {
        var api = new FakeApi { Account = AccountValid, Vip = VipActive };
        var monitor = NewMonitor(api, cookie: "MUSIC_U=x");

        var state = await monitor.RefreshAsync(force: true);

        var valid = Assert.IsType<SessionState.Valid>(state);
        Assert.Equal(1, valid.UserId);
        Assert.Equal("n", valid.Nickname);
        var vip = Assert.IsType<VipStatus.Active>(valid.Vip);
        Assert.Equal(2000, vip.ExpireAt);
        Assert.True(monitor.IsValid);
        Assert.Equal(1, api.VipCalls);
    }

    /// <summary>VIP 拿不到（网络失败 / 301）时显示为未知，**不能**显示成「未开通」。</summary>
    [Fact]
    public async Task VipFailure_IsUnknownNotNone()
    {
        var api = new FakeApi { Account = AccountValid, Vip = null };
        var monitor = NewMonitor(api, cookie: "MUSIC_U=x");

        var state = Assert.IsType<SessionState.Valid>(await monitor.RefreshAsync(force: true));

        Assert.IsType<VipStatus.Unknown>(state.Vip);
    }

    [Fact]
    public async Task Expired_ClearsCookieAndCredentialStore_KeepsWasLoggedIn()
    {
        var api = new FakeApi { Account = AccountNull };
        var settings = new FakeSettings();
        var credentials = new FakeCredentials("MUSIC_U=x");
        using var http = new NcmHttp();
        http.Cookie = "MUSIC_U=x";
        var monitor = new SessionMonitor(http, credentials, settings, api);

        var state = await monitor.RefreshAsync(force: true);

        Assert.IsType<SessionState.Expired>(state);
        Assert.Null(http.Cookie);
        Assert.Null(credentials.GetCookie());
        Assert.True(settings.GetBool(SessionMonitor.WasLoggedInKey, false));

        // 「曾登录」标记让界面说得清「登录已失效」而不是「未登录」。
        Assert.True(monitor.HasCredential);
    }

    [Fact]
    public void ApiCode301_MarksExpiredImmediately()
    {
        var settings = new FakeSettings();
        var credentials = new FakeCredentials("MUSIC_U=x");
        using var http = new NcmHttp();
        http.Cookie = "MUSIC_U=x";
        var monitor = new SessionMonitor(http, credentials, settings, new FakeApi());
        var changes = 0;
        monitor.StateChanged += _ => changes++;

        http.ReportBusinessCode(301); // 收藏 / 每日推荐 / vip/info 的 301 都走这里。

        Assert.IsType<SessionState.Expired>(monitor.State);
        Assert.Null(http.Cookie);
        Assert.Equal(1, changes);

        // 非 301 业务码不动状态。
        http.ReportBusinessCode(404);
        Assert.Equal(1, changes);
    }

    [Fact]
    public async Task NetworkFailure_KeepsPreviousStateAndDoesNotCountAsCheck()
    {
        var api = new FakeApi { Account = AccountValid, Vip = VipActive };
        var monitor = NewMonitor(api, cookie: "MUSIC_U=x");
        await monitor.RefreshAsync(force: true);
        var checkedAt = monitor.LastCheckedAt;

        api.Throw = true;
        var state = await monitor.RefreshAsync(force: true);

        Assert.IsType<SessionState.Valid>(state);
        // 没得出结论就不算一次「判定」，否则离线启动后要等满 30 分钟才会再试。
        Assert.Equal(checkedAt, monitor.LastCheckedAt);
    }

    [Fact]
    public async Task RecheckWindow_BlocksUnforcedRefresh_ButForceBypassesIt()
    {
        var api = new FakeApi { Account = AccountValid, Vip = VipActive };
        var now = DateTimeOffset.UnixEpoch;
        var monitor = NewMonitor(api, cookie: "MUSIC_U=x", clock: () => now);
        await monitor.RefreshAsync(force: true);
        Assert.Equal(1, api.AccountCalls);

        // 未满 30 分钟：窗口内的复核直接跳过。
        now = now.AddMinutes(29);
        await monitor.RefreshAsync();
        Assert.Equal(1, api.AccountCalls);

        // 满 30 分钟：允许复核。
        now = now.AddMinutes(2);
        await monitor.RefreshAsync();
        Assert.Equal(2, api.AccountCalls);

        // force 无视窗口（启动 / 登录成功后必须立刻判一次）。
        await monitor.RefreshAsync(force: true);
        Assert.Equal(3, api.AccountCalls);
    }

    [Fact]
    public async Task SignOut_IsLoggedOut_NotExpired()
    {
        var api = new FakeApi { Account = AccountValid, Vip = VipActive };
        var settings = new FakeSettings();
        var monitor = NewMonitor(api, cookie: "MUSIC_U=x", settings: settings);
        await monitor.RefreshAsync(force: true);

        var state = monitor.NoteSignedOut();

        // 主动退出是用户的选择：再显示「登录已失效」就成了骚扰。
        Assert.IsType<SessionState.LoggedOut>(state);
        Assert.False(settings.GetBool(SessionMonitor.WasLoggedInKey, false));
        Assert.False(monitor.HasCredential);
    }

    [Fact]
    public async Task LoggedIn_SetsMarkerAndRefreshesImmediately()
    {
        var api = new FakeApi { Account = AccountValid, Vip = VipActive };
        var settings = new FakeSettings();
        var credentials = new FakeCredentials(null);
        using var http = new NcmHttp();
        var monitor = new SessionMonitor(http, credentials, settings, api);

        var state = await monitor.NoteLoggedInAsync("MUSIC_U=y");

        Assert.IsType<SessionState.Valid>(state);
        Assert.Equal("MUSIC_U=y", http.Cookie);
        Assert.Equal("MUSIC_U=y", credentials.GetCookie());
        Assert.True(settings.GetBool(SessionMonitor.WasLoggedInKey, false));
    }

    /// <summary>升级上来的老用户只有 cookie 没有标记：判定前先把标记补上。</summary>
    [Fact]
    public async Task LegacyCookie_GetsMarkerBeforeJudging()
    {
        var api = new FakeApi { Account = AccountNull };
        var settings = new FakeSettings();
        var monitor = NewMonitor(api, cookie: "MUSIC_U=x", settings: settings);

        var state = await monitor.RefreshAsync(force: true);

        Assert.IsType<SessionState.Expired>(state);
        Assert.True(settings.GetBool(SessionMonitor.WasLoggedInKey, false));
    }

    /// <summary>
    /// 复核期间被判失效（vip/info 回 301 —— 实测里登录失效就是这个码）：
    /// 基于旧 cookie 的复核结果不得把 Expired 覆盖回 Valid（Android 的 expiryGeneration 同此）。
    /// </summary>
    [Fact]
    public async Task ExpiredDuringVipFetch_IsNotOverwrittenByInFlightCheck()
    {
        var api = new FakeApi { Account = AccountValid, Vip = null };
        var settings = new FakeSettings();
        var credentials = new FakeCredentials("MUSIC_U=x");
        using var http = new NcmHttp();
        http.Cookie = "MUSIC_U=x";
        var monitor = new SessionMonitor(http, credentials, settings, api);
        api.OnVip = () => http.ReportBusinessCode(301);

        var state = await monitor.RefreshAsync(force: true);

        Assert.IsType<SessionState.Expired>(state);
        Assert.IsType<SessionState.Expired>(monitor.State);
        Assert.Null(http.Cookie);
    }

    /// <summary>复核在途时用户主动退出：结果同样不能把 LoggedOut 覆盖回 Valid。</summary>
    [Fact]
    public async Task SignOutDuringVipFetch_IsNotOverwritten()
    {
        var api = new FakeApi { Account = AccountValid, Vip = VipActive };
        using var http = new NcmHttp();
        http.Cookie = "MUSIC_U=x";
        var monitor = new SessionMonitor(http, new FakeCredentials("MUSIC_U=x"), new FakeSettings(), api);
        api.OnVip = () => monitor.NoteSignedOut();

        var state = await monitor.RefreshAsync(force: true);

        Assert.IsType<SessionState.LoggedOut>(state);
        Assert.IsType<SessionState.LoggedOut>(monitor.State);
    }

    private static SessionMonitor NewMonitor(
        FakeApi api,
        string? cookie,
        FakeSettings? settings = null,
        FakeCredentials? credentials = null,
        Func<DateTimeOffset>? clock = null)
    {
        var http = new NcmHttp();
        http.Cookie = cookie;
        return new SessionMonitor(http, credentials ?? new FakeCredentials(cookie), settings ?? new FakeSettings(), api, clock);
    }

    private sealed class FakeApi : ISessionApi
    {
        public string? Account { get; set; }

        public string? Vip { get; set; }

        public bool Throw { get; set; }

        /// <summary>拉 VIP 时顺带做的事（模拟「vip/info 回 301」或「用户此刻退出登录」）。</summary>
        public Action? OnVip { get; set; }

        public int AccountCalls { get; private set; }

        public int VipCalls { get; private set; }

        public Task<JsonValue?> GetAccountAsync(CancellationToken cancellationToken = default)
        {
            AccountCalls++;
            if (Throw)
            {
                throw new HttpRequestException("boom");
            }

            return Task.FromResult(Account == null ? null : JsonValue.Parse(Account));
        }

        public Task<JsonValue?> GetVipInfoAsync(long userId, CancellationToken cancellationToken = default)
        {
            VipCalls++;
            OnVip?.Invoke();
            return Task.FromResult(Vip == null ? null : JsonValue.Parse(Vip));
        }
    }

    private sealed class FakeSettings : ISettingsStore
    {
        private readonly Dictionary<string, bool> _bools = new();

        public int GetInt(string key, int fallback) => fallback;

        public void SetInt(string key, int value)
        {
        }

        public bool GetBool(string key, bool fallback) => _bools.TryGetValue(key, out var value) ? value : fallback;

        public void SetBool(string key, bool value) => _bools[key] = value;

        public string? GetString(string key) => null;

        public void SetString(string key, string? value)
        {
        }
    }

    private sealed class FakeCredentials : ICredentialStore
    {
        private string? _cookie;

        public FakeCredentials(string? cookie) => _cookie = cookie;

        public string? GetCookie() => _cookie;

        public void SetCookie(string cookie) => _cookie = cookie;

        public void ClearCookie() => _cookie = null;
    }
}
