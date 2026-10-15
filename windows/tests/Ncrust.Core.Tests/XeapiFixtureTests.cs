using System.Security.Cryptography;
using System.Text.Json;
using Ncrust.Core.Net.Crypto;
using Xunit;

namespace Ncrust.Core.Tests;

/// <summary>
/// spec/fixtures/crypto/xeapi.json 的逐字节验证。期望值由 <c>spec/tools/xeapi.mjs</c>
/// （零依赖 Node 参考实现）生成，所有随机量固定在夹具里，因此任何一处算法偏差都会暴露。
/// </summary>
public class XeapiFixtureTests
{
    public static IEnumerable<object[]> Ids() => IdsOf();

    [Theory]
    [MemberData(nameof(Ids))]
    public void Xeapi_MatchesFixture(string id)
    {
        var fixture = Fixture(id);
        var input = fixture.GetProperty("input");
        var expect = fixture.GetProperty("expect");

        switch (fixture.GetProperty("kind").GetString())
        {
            case "xeapi-sign":
                Assert.Equal(
                    expect.GetProperty("signature").GetString(),
                    XeapiCrypto.Sign(Text(input, "timestamp"), Text(input, "nonce")));
                break;

            case "xeapi-public-key-decrypt":
                Assert.Equal(
                    expect.GetProperty("plaintext").GetString(),
                    XeapiCrypto.DecryptPublicKey(Text(input, "encryptedData")));
                break;

            case "xeapi-plaintext":
                Assert.Equal(
                    expect.GetProperty("plaintext").GetString(),
                    XeapiCrypto.BuildPlaintext(Pairs(input, "pairs")));
                break;

            case "xeapi-mid-transform":
                Assert.Equal(
                    expect.GetProperty("output").GetString(),
                    Base64(XeapiCrypto.MidTransform(Bytes(input, "ciphertext"), Bytes(input, "random"))));
                break;

            case "xeapi-x25519":
                var privateKey = Bytes(input, "privateKey");
                Assert.Equal(
                    expect.GetProperty("publicKey").GetString(),
                    Base64(XeapiCrypto.X25519PublicFromPrivate(privateKey)));
                Assert.Equal(
                    expect.GetProperty("sharedSecret").GetString(),
                    Base64(XeapiCrypto.X25519(privateKey, Bytes(input, "peerPublicKey"))));
                break;

            case "xeapi-kdf":
                Assert.Equal(
                    expect.GetProperty("aesKey").GetString(),
                    Base64(XeapiCrypto.DeriveAesKey(Bytes(input, "sharedSecret"), Bytes(input, "ephemeralPublicKey"))));
                break;

            case "xeapi-s":
                var s = XeapiCrypto.EncryptS(
                    Bytes(input, "dynamicKey"),
                    Text(input.GetProperty("keyState"), "publicKey"),
                    Text(input.GetProperty("keyState"), "sk"),
                    Text(input, "os"),
                    Bytes(input, "ephemeralPrivate"),
                    Bytes(input, "iv"));
                Assert.Equal(expect.GetProperty("S").GetString(), Base64(s));
                break;

            case "xeapi-request":
                var request = XeapiCrypto.EncryptRequest(
                    Pairs(input, "pairs"),
                    ReadKeyState(input.GetProperty("keyState")),
                    ReadRandom(input.GetProperty("rng")),
                    ReadSession(input),
                    Text(input, "os"));
                Assert.Equal(expect.GetProperty("plaintext").GetString(), request.Plaintext);
                Assert.Equal(expect.GetProperty("C").GetString(), request.C);
                Assert.Equal(expect.GetProperty("S").GetString(), request.S);
                Assert.Equal(expect.GetProperty("R").GetString(), request.R);
                Assert.Equal(expect.GetProperty("body").GetString(), request.Body);
                break;

            case "xeapi-response":
                Assert.Equal(
                    expect.GetProperty("plaintext").GetString(),
                    XeapiCrypto.DecryptResponse(Convert.FromBase64String(Text(input, "base64"))));
                break;

            default:
                throw new InvalidOperationException($"夹具 {id} 的 kind 未知");
        }
    }

    [Fact]
    public void Request_Base64FlavoursMatchProtocol()
    {
        // 协议对两种 base64 的要求不同（夹具专门覆盖）：C 是 base64url 且**不补** '='，
        // S/R 是标准 base64。这里再锁住长度结构：S = ephPub(32) ‖ iv(12) ‖ GCM 密文 ‖ tag(16)，
        // 其中 GCM 明文是 "base64(dynamicKey)|os|sk"；R 是 14 字节明文补齐到 16 的一整块。
        var fixture = Fixture("xeapi.request.like-v1");
        var input = fixture.GetProperty("input");

        var request = XeapiCrypto.EncryptRequest(
            Pairs(input, "pairs"),
            ReadKeyState(input.GetProperty("keyState")),
            ReadRandom(input.GetProperty("rng")));

        Assert.DoesNotContain('=', request.C);
        Assert.DoesNotContain('+', request.C);
        Assert.DoesNotContain('/', request.C);

        var schemePlaintextLength =
            Text(input.GetProperty("rng"), "dynamicKey").Length + 1 + "android".Length + 1 +
            Text(input.GetProperty("keyState"), "sk").Length;
        Assert.Equal(32 + 12 + 16 + schemePlaintextLength, Convert.FromBase64String(request.S).Length);
        Assert.Equal(16, Convert.FromBase64String(request.R).Length);
    }

    /// <summary>
    /// netstandard2.0 没有 <c>AesGcm</c>，Core 里是手写的（<see cref="ManagedAesGcm"/>）。
    /// 这里用平台的实现逐字节交叉比对；长度特意覆盖空明文、非 16 倍数、恰好一个块与多块。
    /// </summary>
    [Theory]
    [InlineData(0)]
    [InlineData(1)]
    [InlineData(15)]
    [InlineData(16)]
    [InlineData(17)]
    [InlineData(31)]
    [InlineData(32)]
    [InlineData(33)]
    [InlineData(255)]
    [InlineData(4096)]
    public void ManagedGcm_MatchesFrameworkAesGcm(int plaintextLength)
    {
        var key = RandomNumberGenerator.GetBytes(16);
        var nonce = RandomNumberGenerator.GetBytes(12);
        var plaintext = RandomNumberGenerator.GetBytes(plaintextLength);

        var ciphertext = ManagedAesGcm.Encrypt(key, nonce, plaintext, out var tag);

        var expectedCiphertext = new byte[plaintextLength];
        var expectedTag = new byte[ManagedAesGcm.TagSize];
        using (var gcm = new AesGcm(key, ManagedAesGcm.TagSize))
        {
            gcm.Encrypt(nonce, plaintext, expectedCiphertext, expectedTag);
        }

        Assert.Equal(expectedCiphertext, ciphertext);
        Assert.Equal(expectedTag, tag);
    }

    [Fact]
    public void ManagedGcm_MatchesFrameworkAesGcm_ForRandomisedInputs()
    {
        for (var round = 0; round < 25; round++)
        {
            var key = RandomNumberGenerator.GetBytes(16);
            var nonce = RandomNumberGenerator.GetBytes(12);
            var plaintext = RandomNumberGenerator.GetBytes(Random.Shared.Next(0, 200));

            var ciphertext = ManagedAesGcm.Encrypt(key, nonce, plaintext, out var tag);

            var expectedCiphertext = new byte[plaintext.Length];
            var expectedTag = new byte[ManagedAesGcm.TagSize];
            using (var gcm = new AesGcm(key, ManagedAesGcm.TagSize))
            {
                gcm.Encrypt(nonce, plaintext, expectedCiphertext, expectedTag);
            }

            Assert.Equal(expectedCiphertext, ciphertext);
            Assert.Equal(expectedTag, tag);
        }
    }

    [Fact]
    public void DecryptResponse_RejectsGarbage()
    {
        Assert.False(XeapiCrypto.TryDecryptResponse(null, out _));
        Assert.False(XeapiCrypto.TryDecryptResponse(Array.Empty<byte>(), out _));
        Assert.False(XeapiCrypto.TryDecryptResponse(new byte[] { 1, 2, 3 }, out _));
        Assert.Throws<CryptographicException>(() => XeapiCrypto.DecryptResponse(new byte[] { 1, 2, 3 }));
    }

    private static IEnumerable<object[]> IdsOf() =>
        Spec.LoadJson("fixtures", "crypto", "xeapi.json")
            .RootElement
            .EnumerateArray()
            .Select(e => new object[] { e.GetProperty("id").GetString()! })
            .ToList();

    private static JsonElement Fixture(string id)
    {
        using var document = Spec.LoadJson("fixtures", "crypto", "xeapi.json");
        foreach (var element in document.RootElement.EnumerateArray())
        {
            if (element.GetProperty("id").GetString() == id)
            {
                return element.Clone();
            }
        }

        throw new InvalidOperationException($"找不到夹具 xeapi.json#{id}");
    }

    private static string Text(JsonElement element, string name) =>
        element.GetProperty(name).GetString()!;

    private static byte[] Bytes(JsonElement element, string name) =>
        Convert.FromBase64String(Text(element, name));

    private static string Base64(byte[] data) => Convert.ToBase64String(data);

    private static List<KeyValuePair<string, string>> Pairs(JsonElement element, string name) =>
        element.GetProperty(name)
            .EnumerateArray()
            .Select(pair => new KeyValuePair<string, string>(pair[0].GetString()!, pair[1].GetString()!))
            .ToList();

    private static XeapiKeyState ReadKeyState(JsonElement element) =>
        new(Text(element, "publicKey"), Text(element, "version"), Text(element, "sk"));

    private static XeapiRandom ReadRandom(JsonElement element) =>
        new(Bytes(element, "dynamicKey"), Bytes(element, "midRandom"), Bytes(element, "ephemeralPrivate"), Bytes(element, "iv"));

    private static XeapiSession? ReadSession(JsonElement input)
    {
        var session = input.GetProperty("session");
        return session.ValueKind == JsonValueKind.Null
            ? null
            : new XeapiSession(session.GetProperty("id").GetString()!, session.GetProperty("key").GetString()!);
    }
}
