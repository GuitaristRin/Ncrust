using System.Text.Json;
using Ncrust.Core.Net.Crypto;
using Xunit;

namespace Ncrust.Core.Tests;

/// <summary>
/// spec/fixtures/crypto 的逐字节验证。期望值由独立参考实现生成（见该目录 README），
/// 各端实现必须与它一致，否则同一份协议在三端会各踩一次坑。
/// </summary>
public class CryptoFixtureTests
{
    public static IEnumerable<object[]> EapiIds() => IdsOf("eapi.json");

    public static IEnumerable<object[]> WeapiIds() => IdsOf("weapi.json");

    [Theory]
    [MemberData(nameof(EapiIds))]
    public void Eapi_MatchesFixture(string id)
    {
        var fixture = Fixture("eapi.json", id);
        var input = fixture.GetProperty("input");
        var expect = fixture.GetProperty("expect");

        switch (fixture.GetProperty("kind").GetString())
        {
            case "eapi-encrypt":
                Assert.Equal(
                    expect.GetProperty("params").GetString(),
                    EapiCrypto.EncryptParams(
                        input.GetProperty("url").GetString()!,
                        input.GetProperty("payloadJson").GetString()!));
                break;
            case "eapi-decrypt":
                Assert.Equal(
                    expect.GetProperty("plaintext").GetString(),
                    EapiCrypto.DecryptResponse(input.GetProperty("base64").GetString()!));
                break;
            default:
                throw new InvalidOperationException($"夹具 {id} 的 kind 未知");
        }
    }

    [Theory]
    [MemberData(nameof(WeapiIds))]
    public void Weapi_MatchesFixture(string id)
    {
        var fixture = Fixture("weapi.json", id);
        var input = fixture.GetProperty("input");
        var expect = fixture.GetProperty("expect");

        var (parameters, encSecKey) = WeapiCrypto.EncryptParams(
            input.GetProperty("payloadJson").GetString()!,
            input.GetProperty("secKey").GetString()!);

        Assert.Equal(expect.GetProperty("params").GetString(), parameters);
        Assert.Equal(expect.GetProperty("encSecKey").GetString(), encSecKey);
    }

    [Fact]
    public void Eapi_PathOnlyDependsOnUrlPath_NotHost()
    {
        var fromPath = EapiCrypto.EncryptParams("/eapi/v2/discovery/recommend/songs", "{}");
        var fromUrl = EapiCrypto.EncryptParams("https://interface.music.163.com/eapi/v2/discovery/recommend/songs", "{}");
        Assert.Equal(fromPath, fromUrl);
    }

    [Fact]
    public void Eapi_DecryptResponse_ReturnsEmptyForGarbage()
    {
        Assert.Equal(string.Empty, EapiCrypto.DecryptResponse(string.Empty));
        Assert.Equal(string.Empty, EapiCrypto.DecryptResponse("not base64!!"));
        Assert.Equal(string.Empty, EapiCrypto.DecryptResponse("Zm9v")); // 3 bytes, not a block multiple
    }

    [Fact]
    public void Weapi_RandomSecKey_IsSixteenAsciiAndProduces256Hex()
    {
        var secret = WeapiCrypto.RandomSecKey();
        Assert.Equal(16, secret.Length);
        Assert.All(secret, c => Assert.True(char.IsLetterOrDigit(c) && c < 128));

        var (parameters, encSecKey) = WeapiCrypto.EncryptParams("{\"a\":\"b\"}");
        Assert.NotEmpty(parameters);
        Assert.Equal(256, encSecKey.Length);
        Assert.All(encSecKey, c => Assert.True((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')));
    }

    [Fact]
    public void Weapi_RejectsWrongSecretLength()
    {
        Assert.Throws<ArgumentException>(() => WeapiCrypto.EncryptParams("{}", "short"));
    }

    private static IEnumerable<object[]> IdsOf(string file) =>
        Spec.LoadJson("fixtures", "crypto", file)
            .RootElement
            .EnumerateArray()
            .Select(e => new object[] { e.GetProperty("id").GetString()! })
            .ToList();

    private static JsonElement Fixture(string file, string id)
    {
        using var document = Spec.LoadJson("fixtures", "crypto", file);
        foreach (var element in document.RootElement.EnumerateArray())
        {
            if (element.GetProperty("id").GetString() == id)
            {
                return element.Clone();
            }
        }

        throw new InvalidOperationException($"找不到夹具 {file}#{id}");
    }
}
