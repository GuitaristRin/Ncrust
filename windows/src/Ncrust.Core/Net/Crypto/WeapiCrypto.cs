using System;
using System.Numerics;
using System.Security.Cryptography;
using System.Text;

namespace Ncrust.Core.Net.Crypto
{
    /// <summary>
    /// weapi 加密方案（对应 Android <c>WeapiCrypto.kt</c>，与网易官方 WeAPI 一致）。
    ///
    /// <list type="number">
    ///   <item>随机 16 字符 secKey；</item>
    ///   <item><c>params = Base64(AES-128-CBC(AES-128-CBC(json, presetKey), secKey))</c>，IV 固定；</item>
    ///   <item><c>encSecKey = (BigInt(reverse(secKey)) ^ e mod n)</c> 的 256 位小写 hex，**无填充、非 base64**。</item>
    /// </list>
    ///
    /// 两个易错点：params 是**两层** AES；encSecKey 是 raw RSA 而不是 PKCS#1 填充。
    /// </summary>
    public static class WeapiCrypto
    {
        public const string PresetKey = "0CoJUm6Qyw8W8jud";

        private const string AesIv = "0102030405060708";
        private const string SecKeyChars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

        // 官方 weapi 公钥（modulus + exponent 0x10001）。
        private const string PublicKeyModulusHex =
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b7251" +
            "52b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ec" +
            "bda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d81" +
            "3cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7";

        private const string PublicKeyExponentHex = "010001";

        /// <summary>用随机 secKey 生成 weapi 的 <c>params</c> 与 <c>encSecKey</c>。</summary>
        public static (string Params, string EncSecKey) EncryptParams(string json) =>
            EncryptParams(json, RandomSecKey());

        /// <summary>
        /// 用固定 <paramref name="secKey"/> 生成 weapi 表单字段。夹具靠它复现输出，
        /// 生产路径请用无参重载（随机 key）。
        /// </summary>
        public static (string Params, string EncSecKey) EncryptParams(string json, string secKey)
        {
            if (secKey == null)
            {
                throw new ArgumentNullException(nameof(secKey));
            }

            if (secKey.Length != 16)
            {
                throw new ArgumentException("secKey 必须是 16 字符", nameof(secKey));
            }

            var parameters = AesCbcEncryptBase64(AesCbcEncryptBase64(json, PresetKey), secKey);
            return (parameters, RsaEncryptHex(secKey));
        }

        /// <summary>从 [a-zA-Z0-9] 里取 16 字符密钥。</summary>
        public static string RandomSecKey()
        {
            var bytes = new byte[16];
            using (var rng = RandomNumberGenerator.Create())
            {
                rng.GetBytes(bytes);
            }

            var sb = new StringBuilder(16);
            foreach (var b in bytes)
            {
                sb.Append(SecKeyChars[b % SecKeyChars.Length]);
            }

            return sb.ToString();
        }

        private static string AesCbcEncryptBase64(string data, string key)
        {
            using (var aes = Aes.Create())
            {
                aes.Key = Encoding.UTF8.GetBytes(key);
                aes.IV = Encoding.UTF8.GetBytes(AesIv);
                aes.Mode = CipherMode.CBC;
                aes.Padding = PaddingMode.PKCS7;
                using (var encryptor = aes.CreateEncryptor())
                {
                    var bytes = Encoding.UTF8.GetBytes(data);
                    return Convert.ToBase64String(encryptor.TransformFinalBlock(bytes, 0, bytes.Length));
                }
            }
        }

        /// <summary>secKey 字符串反转 → big-endian 字节 → m^e mod n → 256 位小写 hex。</summary>
        private static string RsaEncryptHex(string secKey)
        {
            var modulus = FromHexBigEndian(PublicKeyModulusHex);
            var exponent = FromHexBigEndian(PublicKeyExponentHex);

            var chars = secKey.ToCharArray();
            Array.Reverse(chars);
            var message = FromBytesBigEndian(Encoding.UTF8.GetBytes(new string(chars)));

            var encrypted = BigInteger.ModPow(message, exponent, modulus);

            // 结果是 1024 位正数；最高位为 1 时 BigInteger 的 "x" 会带一个 0x00 符号字节，
            // 表现为多一位前导 0。先去掉前导零再补足 256 位，才能与官方实现的 raw RSA 一致。
            var hex = encrypted.ToString("x").TrimStart('0');
            return (hex.Length == 0 ? "0" : hex).PadLeft(256, '0');
        }

        private static BigInteger FromHexBigEndian(string hex)
        {
            var bytes = new byte[hex.Length / 2];
            for (var i = 0; i < bytes.Length; i++)
            {
                bytes[i] = Convert.ToByte(hex.Substring(i * 2, 2), 16);
            }

            return FromBytesBigEndian(bytes);
        }

        /// <summary>
        /// BigInteger 的 byte[] 构造是 little-endian 且有符号；反转后再补一个 0x00
        /// 保证被当作正数（对应 Android 的 <c>BigInteger(1, bytes)</c>）。
        /// </summary>
        private static BigInteger FromBytesBigEndian(byte[] bigEndian)
        {
            var littleEndian = new byte[bigEndian.Length + 1];
            for (var i = 0; i < bigEndian.Length; i++)
            {
                littleEndian[i] = bigEndian[bigEndian.Length - 1 - i];
            }

            littleEndian[bigEndian.Length] = 0;
            return new BigInteger(littleEndian);
        }
    }
}
