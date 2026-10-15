using System.Text.Json;
using Ncrust.Core.Account;
using Ncrust.Core.Json;
using Xunit;

namespace Ncrust.Core.Tests;

/// <summary>
/// spec/fixtures/entitlement 的三组夹逐一验证：playback / vip / session。
///
/// 夹具是手写的规则答案（生成脚本 <c>spec/tools/gen-entitlement-fixtures.mjs</c>，输入取自
/// 2026-10-15 的匿名实测响应），所以这里的断言只做「实现与规格一致」的翻译，不做事后解释：
/// 判定函数写错一条分支，对应的那条用例就红。
/// </summary>
public class EntitlementFixtureTests
{
    /// <summary>
    /// VIP 判定的本机时间兜底值。夹具的每个用例都自带服务端 <c>data.now</c>，
    /// 用固定值是为了不把测试绑在墙上时钟上（真走到兜底的话结果也是确定的）。
    /// </summary>
    private const long FixtureLocalNowMs = 1792000000000;

    public static IEnumerable<object[]> PlaybackIds() => IdsOf("playback");

    public static IEnumerable<object[]> VipIds() => IdsOf("vip");

    public static IEnumerable<object[]> SessionIds() => IdsOf("session");

    [Theory]
    [MemberData(nameof(PlaybackIds))]
    public void Playback_MatchesFixture(string id)
    {
        var fixture = Fixture("playback", id);
        var input = fixture.GetProperty("input");
        var expect = fixture.GetProperty("expect");

        var item = input.GetProperty("item").ValueKind == JsonValueKind.Null
            ? null
            : PlaybackItem.FromJson(Parse(input.GetProperty("item")));
        var privilege = input.GetProperty("privilege").ValueKind == JsonValueKind.Null
            ? null
            : SongPrivilege.FromJson(Parse(input.GetProperty("privilege")));

        var result = PlaybackEntitlement.Classify(
            Text(input, "requestedLevel"),
            input.GetProperty("sessionValid").GetBoolean(),
            item,
            privilege);

        switch (Text(expect, "kind"))
        {
            case "full":
                var full = Assert.IsType<PlaybackEntitlement.Full>(result);
                Assert.Equal(expect.GetProperty("capped").GetBoolean(), full.Capped);
                if (full.Capped)
                {
                    Assert.Equal(Text(expect, "cappedTo"), full.CappedTo);
                    Assert.Equal(Text(expect, "cappedReason"), full.CappedReason!.Value.Wire());
                }
                else
                {
                    Assert.Null(full.CappedTo);
                    Assert.Null(full.CappedReason);
                }

                break;

            case "trial":
                var trial = Assert.IsType<PlaybackEntitlement.Trial>(result);
                Assert.Equal(expect.GetProperty("trialSeconds").GetInt32(), trial.TrialSeconds);
                Assert.Equal(Text(expect, "reason"), trial.Reason.Wire());
                break;

            case "unavailable":
                var unavailable = Assert.IsType<PlaybackEntitlement.Unavailable>(result);
                Assert.Equal(Text(expect, "reason"), unavailable.Reason.Wire());
                break;

            default:
                throw new InvalidOperationException($"夹具 {id} 的 expect.kind 未知");
        }
    }

    [Theory]
    [MemberData(nameof(VipIds))]
    public void Vip_MatchesFixture(string id)
    {
        var fixture = Fixture("vip", id);
        var input = fixture.GetProperty("input");
        var expect = fixture.GetProperty("expect");

        var response = input.GetProperty("response").ValueKind == JsonValueKind.Null
            ? null
            : Parse(input.GetProperty("response"));

        var state = VipStatus.Derive(response, FixtureLocalNowMs);

        switch (Text(expect, "state"))
        {
            case "active":
                var active = Assert.IsType<VipStatus.Active>(state);
                Assert.Equal(Text(expect, "kind"), active.Kind.Wire());
                Assert.Equal(expect.GetProperty("expireAt").GetInt64(), active.ExpireAt);
                break;

            case "lapsed":
                var lapsed = Assert.IsType<VipStatus.Lapsed>(state);
                Assert.Equal(expect.GetProperty("expireAt").GetInt64(), lapsed.ExpireAt);
                if (expect.TryGetProperty("kind", out var kind))
                {
                    Assert.Equal(kind.GetString(), lapsed.Kind.Wire());
                }

                break;

            case "none":
                Assert.IsType<VipStatus.None>(state);
                break;

            case "unknown":
                Assert.IsType<VipStatus.Unknown>(state);
                break;

            default:
                throw new InvalidOperationException($"夹具 {id} 的 expect.state 未知");
        }
    }

    [Theory]
    [MemberData(nameof(SessionIds))]
    public void Session_MatchesFixture(string id)
    {
        var fixture = Fixture("session", id);
        var input = fixture.GetProperty("input");
        var expect = fixture.GetProperty("expect");

        // accountGet 可能整段缺省（301 用例只看业务码）。
        var accountGet = !input.TryGetProperty("accountGet", out var account) || account.ValueKind == JsonValueKind.Null
            ? null
            : Parse(account);
        int? otherApiCode = input.TryGetProperty("otherApiCode", out var code) && code.ValueKind == JsonValueKind.Number
            ? code.GetInt32()
            : null;

        var state = SessionState.Derive(input.GetProperty("hasCookie").GetBoolean(), accountGet, otherApiCode);

        Assert.Equal(Text(expect, "state"), StateName(state));
    }

    /// <summary>
    /// 有 cookie + 账号资料齐全 → Valid，且资料取自 profile（缺失字段退回 account）。
    /// 夹具只锁状态名，这里补上「Valid 里带出来的资料对不对」。
    /// </summary>
    [Fact]
    public void Session_ValidCarriesProfile()
    {
        var state = SessionState.Derive(
            hasCredential: true,
            Parse("{\"code\":200,\"account\":{\"id\":7,\"userName\":\"a\"}," +
                  "\"profile\":{\"userId\":9,\"nickname\":\"n\",\"avatarUrl\":\"http://a\"}}"));

        var valid = Assert.IsType<SessionState.Valid>(state);
        Assert.Equal(9, valid.UserId);
        Assert.Equal("n", valid.Nickname);
        Assert.Equal("http://a", valid.AvatarUrl);
        Assert.IsType<VipStatus.Unknown>(valid.Vip); // VIP 由 SessionMonitor 另外拉。
    }

    /// <summary>夹具只覆盖「有 cookie 且 account 为空 → expired」；没有凭证又没有「曾登录」标记时必须判未登录。</summary>
    [Fact]
    public void Session_NoCredentialIsLoggedOut()
    {
        Assert.IsType<SessionState.LoggedOut>(
            SessionState.Derive(hasCredential: false, Parse("{\"code\":200,\"account\":null,\"profile\":null}")));
    }

    /// <summary>301 优先于 account/get 的结果：服务端的明确答复不能被一次 200 覆盖。</summary>
    [Fact]
    public void Session_ApiCodeWins()
    {
        Assert.IsType<SessionState.Expired>(
            SessionState.Derive(
                hasCredential: true,
                Parse("{\"code\":200,\"account\":{\"id\":1},\"profile\":{\"userId\":1}}"),
                301));
    }

    [Fact]
    public void EntitlementReason_WireRoundTrips()
    {
        foreach (var wire in new[] { "vip", "album", "login", "copyright", "unknown" })
        {
            var reason = EntitlementReasonExtensions.FromWire(wire);
            Assert.NotNull(reason);
            Assert.Equal(wire, reason!.Value.Wire());
        }

        Assert.Null(EntitlementReasonExtensions.FromWire("nope"));
    }

    private static string StateName(SessionState state)
    {
        if (state is SessionState.Valid)
        {
            return "valid";
        }

        if (state is SessionState.Expired)
        {
            return "expired";
        }

        return state is SessionState.LoggedOut ? "logged-out" : "unknown";
    }

    private static IEnumerable<object[]> IdsOf(string kind) =>
        Spec.LoadJson("fixtures", "entitlement", kind + ".json")
            .RootElement
            .EnumerateArray()
            .Select(e => new object[] { e.GetProperty("id").GetString()! })
            .ToList();

    private static JsonElement Fixture(string kind, string id)
    {
        using var document = Spec.LoadJson("fixtures", "entitlement", kind + ".json");
        foreach (var element in document.RootElement.EnumerateArray())
        {
            if (element.GetProperty("id").GetString() == id)
            {
                return element.Clone();
            }
        }

        throw new InvalidOperationException($"找不到夹具 entitlement/{kind}.json#{id}");
    }

    /// <summary>JsonElement → Core 的只读 DOM（线上链路同样是 JsonValue 解析，字段映射才一致）。</summary>
    private static JsonValue Parse(JsonElement element) => Parse(element.GetRawText());

    private static JsonValue Parse(string json) => JsonValue.Parse(json);

    private static string Text(JsonElement element, string name) => element.GetProperty(name).GetString()!;
}
