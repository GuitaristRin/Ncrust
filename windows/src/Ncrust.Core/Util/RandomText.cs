using System;
using System.Text;

namespace Ncrust.Core.Util
{
    /// <summary>
    /// 随机 id / 十六进制串（小写，与 Android 的 <c>bytesToHex</c> 一致）。
    /// 设备 id 形态的字段（如 eapi iPhone 指纹的 deviceId）由调用方决定长度。
    /// </summary>
    internal static class RandomText
    {
        private const string LowerHexChars = "0123456789abcdef";

        private static readonly object Lock = new object();
        private static readonly Random Rand = new Random();

        public static int Next(int minInclusive, int maxExclusive)
        {
            lock (Lock)
            {
                return Rand.Next(minInclusive, maxExclusive);
            }
        }

        /// <summary>小写十六进制。</summary>
        public static string Hex(int length) => Build(length, LowerHexChars);

        private static string Build(int length, string chars)
        {
            var sb = new StringBuilder(length);
            lock (Lock)
            {
                for (var i = 0; i < length; i++)
                {
                    sb.Append(chars[Rand.Next(chars.Length)]);
                }
            }

            return sb.ToString();
        }
    }
}
