using System;
using System.Security.Cryptography;
using System.Text;

namespace Ncrust.Core.Net.Crypto
{
    /// <summary>eapi / weapi 共用的十六进制与摘要辅助。</summary>
    internal static class CryptoUtil
    {
        /// <summary>小写十六进制，Android <c>bytesToHex</c> 同款。</summary>
        internal static string ToHex(byte[] bytes)
        {
            var sb = new StringBuilder(bytes.Length * 2);
            foreach (var b in bytes)
            {
                sb.Append(b.ToString("x2"));
            }
            return sb.ToString();
        }

        internal static string Md5Hex(string input)
        {
            using (var md5 = MD5.Create())
            {
                return ToHex(md5.ComputeHash(Encoding.UTF8.GetBytes(input)));
            }
        }

        /// <summary>
        /// 密码学随机字节。用 <see cref="RandomNumberGenerator"/> 而不是 <c>Random</c>：
        /// 动态密钥、临时私钥、设备 id 都属于不该被预测的量。
        /// </summary>
        internal static byte[] RandomBytes(int length)
        {
            var bytes = new byte[length];
            using (var rng = RandomNumberGenerator.Create())
            {
                rng.GetBytes(bytes);
            }

            return bytes;
        }
    }
}
