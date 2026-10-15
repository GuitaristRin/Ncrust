using System.Text.Json;
using Ncrust.Core.Update;
using Xunit;

namespace Ncrust.Core.Tests;

public class UpdateCheckerTests
{
    /// <summary>
    /// spec/fixtures/update/releases.json 是两端共用的「挑更新」规则答案（schema 见该目录 README）。
    /// 这里把夹具用例逐条喂给 Windows 的 UpdateChecker，锁住跨班次取最高、draft 忽略、
    /// 资产命名规范、平台/架构不互相误命中这些两端必须一致的行为。
    ///
    /// 夹具用例目前全部两端适用（prerelease 与扩展名两条已在 Windows 补齐）；若将来有用例只标了
    /// 一端，这里会显式跳过并断言出来，不假装覆盖。
    /// </summary>
    [Fact]
    public void Find_MatchesUpdateFixture()
    {
        using var doc = Spec.LoadJson("fixtures", "update", "releases.json");
        var cases = doc.RootElement;
        Assert.Equal(JsonValueKind.Array, cases.ValueKind);

        var checkedCases = 0;
        var skippedForWindows = new List<string>();

        foreach (var testCase in cases.EnumerateArray())
        {
            var id = testCase.GetProperty("id").GetString()!;
            if (!AppliesToWindows(testCase))
            {
                skippedForWindows.Add(id);
                continue;
            }

            var input = testCase.GetProperty("input");
            var current = AppVersion.Parse(input.GetProperty("currentVersion").GetString()!);
            var platform = input.GetProperty("platform").GetString()!;
            var arch = input.GetProperty("arch").GetString()!;
            // 夹具里存的是结构化 JSON，UpdateChecker 收的是 API 原文，所以取原始文本。
            var releasesJson = input.GetProperty("releases").GetRawText();

            var info = UpdateChecker.Find(releasesJson, current, platform, arch);

            var expect = testCase.GetProperty("expect");
            if (expect.ValueKind == JsonValueKind.Null)
            {
                Assert.Null(info);
            }
            else
            {
                Assert.NotNull(info);
                Assert.Equal(AppVersion.Parse(expect.GetProperty("version").GetString()!), info!.Version);
                Assert.Equal(expect.GetProperty("releaseTag").GetString(), info.ReleaseTag);
                Assert.Equal(expect.GetProperty("releasePageUrl").GetString(), info.ReleasePageUrl);
                if (expect.TryGetProperty("windowsAssets", out var assets))
                {
                    Assert.Equal(OptionalString(assets, "appInstallerUrl"), info.AppInstallerUrl);
                    Assert.Equal(OptionalString(assets, "msixUrl"), info.MsixUrl);
                    Assert.Equal(OptionalString(assets, "cerUrl"), info.CerUrl);
                }
            }

            checkedCases++;
        }

        // 夹具被清空、或平台标记写错导致大量用例被跳过时，测试必须红，否则等于没有对照。
        Assert.Equal(cases.GetArrayLength(), checkedCases + skippedForWindows.Count);
        Assert.True(checkedCases >= 12, $"跑到的 Windows 用例太少：{checkedCases}");
        Assert.Empty(skippedForWindows);
    }

    /// <summary>夹具用例默认两端都适用；显式写了 platforms 的按它判断。</summary>
    private static bool AppliesToWindows(JsonElement testCase) =>
        !testCase.TryGetProperty("platforms", out var platforms) ||
        platforms.EnumerateArray().Any(p => p.GetString() == "windows");

    private static string? OptionalString(JsonElement obj, string name) =>
        obj.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String
            ? value.GetString()
            : null;

    [Theory]
    [InlineData("1.0.1", true, 1, 0, 1, 0)]
    [InlineData("v1.0.1", true, 1, 0, 1, 0)]
    [InlineData("1.0.1.0", true, 1, 0, 1, 0)]
    [InlineData("2", true, 2, 0, 0, 0)]
    [InlineData("1.2", true, 1, 2, 0, 0)]
    [InlineData("1.0.1.0.5", false, 0, 0, 0, 0)]
    [InlineData("", false, 0, 0, 0, 0)]
    [InlineData("a.b", false, 0, 0, 0, 0)]
    [InlineData("1..0", false, 0, 0, 0, 0)]
    [InlineData("-1.0", false, 0, 0, 0, 0)]
    public void AppVersion_TryParse(string text, bool ok, int major, int minor, int build, int revision)
    {
        var parsed = AppVersion.TryParse(text, out var version);

        Assert.Equal(ok, parsed);
        if (ok)
        {
            Assert.Equal(major, version.Major);
            Assert.Equal(minor, version.Minor);
            Assert.Equal(build, version.Build);
            Assert.Equal(revision, version.Revision);
        }
    }

    [Fact]
    public void Find_FindsHigherWindowsRelease()
    {
        var json = "[" + Release(
            "CrateStack-20260928A",
            Asset("ncrust-windows-x86_64-1.0.1.appinstaller", "https://d/app.appinstaller"),
            Asset("ncrust-windows-x86_64-1.0.1.msix", "https://d/app.msix"),
            Asset("ncrust-windows-x86_64-1.0.1.cer", "https://d/app.cer"),
            Asset("ncrust-android-universal-1.3.2.apk", "https://d/app.apk")) + "]";

        var info = UpdateChecker.Find(json, new AppVersion(1, 0, 0));

        Assert.NotNull(info);
        Assert.Equal("1.0.1", info!.Version.ToString());
        Assert.Equal("https://d/app.appinstaller", info.AppInstallerUrl);
        Assert.Equal("https://d/app.msix", info.MsixUrl);
        Assert.Equal("https://d/app.cer", info.CerUrl);
        Assert.Equal("CrateStack-20260928A", info.ReleaseTag);
    }

    [Fact]
    public void Find_ReturnsNullWhenUpToDate()
    {
        var json = "[" + Release("CrateStack-1", Asset("ncrust-windows-x86_64-1.0.0.msix", "https://d/a.msix")) + "]";

        Assert.Null(UpdateChecker.Find(json, new AppVersion(1, 0, 0)));
        Assert.Null(UpdateChecker.Find(json, new AppVersion(1, 0, 1)));
    }

    [Fact]
    public void Find_PicksHighestVersionAcrossReleases()
    {
        var json = "[" +
            Release("CrateStack-1", Asset("ncrust-windows-x86_64-1.0.1.msix", "https://d/1.msix")) + "," +
            Release("CrateStack-2", Asset("ncrust-windows-x86_64-1.0.2.msix", "https://d/2.msix")) + "]";

        var info = UpdateChecker.Find(json, new AppVersion(1, 0, 0));

        Assert.NotNull(info);
        Assert.Equal("https://d/2.msix", info!.MsixUrl);
    }

    [Fact]
    public void Find_IgnoresDraftAndOtherPlatform()
    {
        var json = "[" +
            Release("draft", true, Asset("ncrust-windows-x86_64-9.9.9.msix", "https://d/draft.msix")) + "," +
            Release("other", Asset("ncrust-windows-arm64-9.9.9.msix", "https://d/arm.msix")) + "]";

        Assert.Null(UpdateChecker.Find(json, new AppVersion(1, 0, 0)));
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("not json")]
    [InlineData("{}")]
    public void Find_ReturnsNullOnGarbage(string? body)
    {
        Assert.Null(UpdateChecker.Find(body, new AppVersion(1, 0, 0)));
    }

    private static string Release(string tag, params string[] assets) => Release(tag, false, assets);

    private static string Release(string tag, bool draft, params string[] assets) =>
        "{\"tag_name\":\"" + tag + "\",\"html_url\":\"https://gh/" + tag + "\",\"draft\":" +
        (draft ? "true" : "false") + ",\"assets\":[" + string.Join(",", assets) + "]}";

    private static string Asset(string name, string url) =>
        "{\"name\":\"" + name + "\",\"browser_download_url\":\"" + url + "\",\"size\":123}";
}
