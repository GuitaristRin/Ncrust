using System;
using System.Security.Cryptography;
using System.Text;
using Ncrust.Core.Net.Crypto;
using Xunit;

namespace Ncrust.Core.Tests;

/// <summary>
/// 测试侧解 eapi 请求体。请求体是 <c>params=&lt;hex&gt;</c>，加密前的明文是
/// <c>&lt;path&gt;-36cd479b6b5-&lt;json&gt;-36cd479b6b5-&lt;digest&gt;</c>。
///
/// 两处需要「加密前到底是什么」：扫码登录（AuthTests）与播放上报（PlayReportEapiTests）。
/// </summary>
internal static class EapiTestBody
{
    private const string Separator = "-36cd479b6b5-";

    /// <summary>明文里的业务 JSON 文本。</summary>
    public static string DecryptParams(string body) => Parts(body)[1];

    /// <summary>
    /// 明文里的路径段：请求路径是 <c>/eapi/…</c>，**签名用的是 <c>/api/…</c>** ——
    /// 两者不一致就是签名算错，服务端只会静默丢弃。
    /// </summary>
    public static string SignedPath(string body) => Parts(body)[0];

    private static string[] Parts(string body)
    {
        var value = body.Substring(body.IndexOf('=') + 1);
        var bytes = new byte[value.Length / 2];
        for (var i = 0; i < bytes.Length; i++)
        {
            bytes[i] = Convert.ToByte(value.Substring(i * 2, 2), 16);
        }

        using var aes = Aes.Create();
        aes.Key = EapiCrypto.AesKey;
        aes.Mode = CipherMode.ECB;
        aes.Padding = PaddingMode.PKCS7;
        using var decryptor = aes.CreateDecryptor();
        var plaintext = Encoding.UTF8.GetString(decryptor.TransformFinalBlock(bytes, 0, bytes.Length));

        var parts = plaintext.Split(new[] { Separator }, StringSplitOptions.None);
        Assert.Equal(3, parts.Length);
        return parts;
    }
}
