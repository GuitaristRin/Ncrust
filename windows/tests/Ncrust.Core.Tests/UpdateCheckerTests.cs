using Ncrust.Core.Update;
using Xunit;

namespace Ncrust.Core.Tests;

public class UpdateCheckerTests
{
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
