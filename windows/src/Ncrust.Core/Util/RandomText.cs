using System;
using System.Text;

namespace Ncrust.Core.Util
{
    /// <summary>
    /// 随机 id / 十六进制串。设备 id 等字段的字符集与大小写要跟 Android 一致
    /// （HEX_CHARS 是大写），所以上下两种十六进制都提供。
    /// </summary>
    internal static class RandomText
    {
        private const string LowerHexChars = "0123456789abcdef";
        private const string UpperHexChars = "0123456789ABCDEF";

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

        /// <summary>大写十六进制，与 Android 的 <c>HEX_CHARS</c> 一致。</summary>
        public static string UpperHex(int length) => Build(length, UpperHexChars);

        /// <summary>52 位大写十六进制设备 id，与 go-musicfox 的 sDeviceId 格式一致。</summary>
        public static string SDeviceId() => UpperHex(52);

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
