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
    }
}
