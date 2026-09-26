using System;
using System.Security.Cryptography;
using System.Text;

namespace Ncrust.Core.Net.Crypto
{
    /// <summary>
    /// eapi 加密方案（对应 Android <c>EapiCrypto.kt</c>）。
    ///
    /// 请求：路径里的 <c>/eapi/</c> 重写为 <c>/api/</c> 后，把
    /// <c>nobody&lt;path&gt;use&lt;json&gt;md5forencrypt</c> 的 MD5 当作签名，拼成
    /// <c>&lt;path&gt;-36cd479b6b5-&lt;json&gt;-36cd479b6b5-&lt;digest&gt;</c>，
    /// 用 AES-128-ECB/PKCS5 加密后输出小写十六进制，作为表单 <c>params</c>。
    ///
    /// 响应：部分写接口（如 /eapi/radio/like）返回的是同一把 key 的 AES-ECB 加密 JSON，
    /// base64 解码后解密，并裁掉尾部空白。
    ///
    /// 全部为纯函数、无外部依赖，便于对夹具逐字节验证。
    /// </summary>
    public static class EapiCrypto
    {
        private static readonly byte[] AesKey = Encoding.UTF8.GetBytes("e82ckenh8dichen8");

        private const string MagicPrefix = "nobody";
        private const string MagicSuffix = "md5forencrypt";
        private const string Separator = "-36cd479b6b5-";

        /// <summary>
        /// 生成 eapi 的 <c>params</c>。<paramref name="url"/> 可以是完整 URL，也可以是
        /// <c>/eapi/</c> 开头的路径；只有 path 参与签名与加密。
        /// </summary>
        public static string EncryptParams(string url, string payloadJson)
        {
            var urlPath = ResolvePath(url);
            var digest = CryptoUtil.Md5Hex(MagicPrefix + urlPath + "use" + payloadJson + MagicSuffix);
            var paramsStr = urlPath + Separator + payloadJson + Separator + digest;
            return AesEcbEncryptHex(paramsStr);
        }

        /// <summary>解密 eapi 加密响应。<paramref name="base64Body"/> 非法时返回 <see cref="string.Empty"/>。</summary>
        public static string DecryptResponse(string base64Body)
        {
            if (string.IsNullOrEmpty(base64Body))
            {
                return string.Empty;
            }

            byte[] bytes;
            try
            {
                bytes = Convert.FromBase64String(base64Body);
            }
            catch (FormatException)
            {
                return string.Empty;
            }

            if (bytes.Length == 0 || bytes.Length % 16 != 0)
            {
                return string.Empty;
            }

            try
            {
                using (var aes = Aes.Create())
                {
                    aes.Key = AesKey;
                    aes.Mode = CipherMode.ECB;
                    aes.Padding = PaddingMode.PKCS7;
                    using (var decryptor = aes.CreateDecryptor())
                    {
                        var plain = decryptor.TransformFinalBlock(bytes, 0, bytes.Length);
                        return Encoding.UTF8.GetString(plain).TrimEnd('\r', '\n', ' ');
                    }
                }
            }
            catch (CryptographicException)
            {
                return string.Empty;
            }
        }

        private static string ResolvePath(string url)
        {
            var path = url;
            if (Uri.TryCreate(url, UriKind.Absolute, out var uri))
            {
                path = uri.AbsolutePath;
            }

            return path.Replace("/eapi/", "/api/");
        }

        private static string AesEcbEncryptHex(string text)
        {
            using (var aes = Aes.Create())
            {
                aes.Key = AesKey;
                aes.Mode = CipherMode.ECB;
                aes.Padding = PaddingMode.PKCS7;
                using (var encryptor = aes.CreateEncryptor())
                {
                    var data = Encoding.UTF8.GetBytes(text);
                    var cipher = encryptor.TransformFinalBlock(data, 0, data.Length);
                    return CryptoUtil.ToHex(cipher);
                }
            }
        }
    }
}
