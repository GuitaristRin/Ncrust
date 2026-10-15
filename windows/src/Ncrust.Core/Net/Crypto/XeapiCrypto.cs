using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Numerics;
using System.Security.Cryptography;
using System.Text;

namespace Ncrust.Core.Net.Crypto
{
    /// <summary>
    /// 一次 xeapi 请求要注入的全部随机量。生产调用方用 <see cref="Create"/>（CSPRNG），
    /// 夹具测试注入固定值，因此 <see cref="XeapiCrypto.EncryptRequest"/> 里没有任何随机来源。
    /// </summary>
    public sealed class XeapiRandom
    {
        public XeapiRandom(byte[] dynamicKey, byte[] midRandom, byte[] ephemeralPrivate, byte[] iv)
        {
            DynamicKey = dynamicKey ?? throw new ArgumentNullException(nameof(dynamicKey));
            MidRandom = midRandom ?? throw new ArgumentNullException(nameof(midRandom));
            EphemeralPrivate = ephemeralPrivate ?? throw new ArgumentNullException(nameof(ephemeralPrivate));
            Iv = iv ?? throw new ArgumentNullException(nameof(iv));
        }

        /// <summary>无会话时的动态密钥（16 字节 → AES-128）。</summary>
        public byte[] DynamicKey { get; }

        /// <summary>明文信封中间变换用的 16 字节。</summary>
        public byte[] MidRandom { get; }

        /// <summary>本次请求的 X25519 临时私钥（32 字节）。</summary>
        public byte[] EphemeralPrivate { get; }

        /// <summary>AES-GCM 的 12 字节 IV。</summary>
        public byte[] Iv { get; }

        public static XeapiRandom Create() => new XeapiRandom(
            CryptoUtil.RandomBytes(16),
            CryptoUtil.RandomBytes(16),
            CryptoUtil.RandomBytes(32),
            CryptoUtil.RandomBytes(12));
    }

    /// <summary>
    /// 公钥接口下发的密钥状态。除 <see cref="PublicKey"/> 外都参与请求：
    /// <see cref="Version"/> 解密进 <c>R</c>，<see cref="Sk"/> 明文进 <c>S</c>。
    /// </summary>
    public sealed class XeapiKeyState
    {
        public XeapiKeyState(string publicKey, string version, string sk, long nextUpdateTime = 0)
        {
            PublicKey = publicKey ?? throw new ArgumentNullException(nameof(publicKey));
            Version = version ?? string.Empty;
            Sk = sk ?? string.Empty;
            NextUpdateTime = nextUpdateTime;
        }

        /// <summary>32 字节 X25519 原始公钥的 base64。</summary>
        public string PublicKey { get; }

        public string Version { get; }

        public string Sk { get; }

        /// <summary>毫秒级时间戳，过期后要重新取公钥。</summary>
        public long NextUpdateTime { get; }
    }

    /// <summary>响应头 <c>x-encr-ssid</c> / <c>x-encr-sskey</c> 建立的会话。只存内存。</summary>
    public sealed class XeapiSession
    {
        public XeapiSession(string id, string key)
        {
            Id = id ?? string.Empty;
            Key = key ?? string.Empty;
        }

        public string Id { get; }

        /// <summary>32 个 ASCII 字符，其 UTF-8 字节直接当 AES-256 密钥用。</summary>
        public string Key { get; }
    }

    /// <summary><see cref="XeapiCrypto.EncryptRequest"/> 的产物，字段与协议 §3.2 一一对应。</summary>
    public sealed class XeapiRequest
    {
        internal XeapiRequest(string plaintext, string c, string s, string r, string body)
        {
            Plaintext = plaintext;
            C = c;
            S = s;
            R = r;
            Body = body;
        }

        /// <summary>明文信封 JSON 文本（加密前）。</summary>
        public string Plaintext { get; }

        /// <summary>base64url 且不补 <c>=</c>。</summary>
        public string C { get; }

        /// <summary>标准 base64（带 <c>=</c>）。</summary>
        public string S { get; }

        /// <summary>标准 base64（带 <c>=</c>）。</summary>
        public string R { get; }

        /// <summary>表单编码后的请求体 <c>C=…&amp;S=…&amp;R=…</c>。</summary>
        public string Body { get; }
    }

    /// <summary>
    /// xeapi —— 官方 Android 9.x 的请求加密层（协议见 <c>spec/api/xeapi.md</c>，
    /// 参考实现 <c>spec/tools/xeapi.mjs</c>）。收藏写入走它是因为老通道
    /// （weapi / eapi <c>/api/radio/like</c>）的写会被服务端风控事后回滚。
    ///
    /// 全部为纯函数、无外部依赖、无隐式随机：随机量由 <see cref="XeapiRandom"/> 注入，
    /// 所以夹具可以逐字节比对。AES-GCM 见 <see cref="ManagedAesGcm"/>（netstandard2.0 没有 AesGcm）。
    /// </summary>
    public static class XeapiCrypto
    {
        /// <summary>公钥请求与业务请求共用的官方 Android UA。</summary>
        public const string AndroidUserAgent =
            "NeteaseMusic/9.5.61.260802021928(9005061);Dalvik/2.1.0 (Linux; U; Android 12; HBN-AL00 Build/cd737a2.0)";

        private const string SignKey =
            "mUHCwVNWJbunMqAHf5MImuirT6plvs6VSFW62MGHstFQxhBGdEoIhLItH3djc4+FB/OKty3+lL2rGeoFBpVe5g==";

        private static readonly byte[] StaticKey = HexToBytes(
            "ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84");

        // 25519 曲线的参数与基准点（RFC 7748 §4.1）。
        private static readonly BigInteger P = BigInteger.Pow(2, 255) - 19;
        private static readonly BigInteger A24 = 121665;
        private static readonly byte[] BasePoint = { 9, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };

        /// <summary>
        /// 公钥接口的请求签名：<c>Base64(HMAC-SHA256(SIGN_KEY 的 ASCII 字节, timestamp + nonce))</c>。
        /// 注意 key 是那串字符本身，**不做 base64 解码**。
        /// </summary>
        public static string Sign(string timestamp, string nonce)
        {
            using (var hmac = new HMACSHA256(Encoding.ASCII.GetBytes(SignKey)))
            {
                return Convert.ToBase64String(
                    hmac.ComputeHash(Encoding.UTF8.GetBytes(timestamp + nonce)));
            }
        }

        /// <summary>公钥接口 <c>data.encryptedData</c> → 公钥状态 JSON 文本（AES-256-ECB，静态 key）。</summary>
        public static string DecryptPublicKey(string encryptedDataBase64) =>
            Encoding.UTF8.GetString(AesEcbDecrypt(StaticKey, Convert.FromBase64String(encryptedDataBase64)));

        /// <summary>
        /// WHATWG <c>application/x-www-form-urlencoded</c>（协议 §4）。
        /// <c>WebUtility.UrlEncode</c> 不编码 <c>! ( )</c>、<c>Uri.EscapeDataString</c> 把空格写成
        /// <c>%20</c> 且不编码 <c>~</c>，两者都与服务端不一致，所以手写。
        /// </summary>
        public static string FormEncode(string value)
        {
            if (string.IsNullOrEmpty(value))
            {
                return string.Empty;
            }

            var bytes = Encoding.UTF8.GetBytes(value);
            var sb = new StringBuilder(bytes.Length);
            foreach (var b in bytes)
            {
                if ((b >= 'A' && b <= 'Z') || (b >= 'a' && b <= 'z') || (b >= '0' && b <= '9') ||
                    b == '*' || b == '-' || b == '.' || b == '_')
                {
                    sb.Append((char)b);
                }
                else if (b == ' ')
                {
                    sb.Append('+');
                }
                else
                {
                    sb.Append('%').Append(b.ToString("X2", CultureInfo.InvariantCulture));
                }
            }

            return sb.ToString();
        }

        /// <summary>有序键值对的表单编码。</summary>
        public static string FormEncode(IEnumerable<KeyValuePair<string, string>> pairs)
        {
            if (pairs == null)
            {
                throw new ArgumentNullException(nameof(pairs));
            }

            var sb = new StringBuilder();
            foreach (var pair in pairs)
            {
                if (sb.Length > 0)
                {
                    sb.Append('&');
                }

                sb.Append(FormEncode(pair.Key)).Append('=').Append(FormEncode(pair.Value));
            }

            return sb.ToString();
        }

        /// <summary>
        /// 明文信封 <c>P</c>：<c>{"content":"&lt;业务字段表单编码，剔除 e_r&gt;","queryString":"e_r=true"}</c>。
        /// 字段顺序是协议的一部分（整段 JSON 参与加密）；没有业务字段时省略 <c>content</c>。
        /// </summary>
        public static string BuildPlaintext(IEnumerable<KeyValuePair<string, string>> pairs)
        {
            if (pairs == null)
            {
                throw new ArgumentNullException(nameof(pairs));
            }

            var fields = new List<KeyValuePair<string, string>>();
            foreach (var pair in pairs)
            {
                if (!string.Equals(pair.Key, "e_r", StringComparison.Ordinal))
                {
                    fields.Add(pair);
                }
            }

            var body = FormEncode(fields);
            var sb = new StringBuilder();
            sb.Append('{');
            if (body.Length > 0)
            {
                sb.Append("\"content\":").Append(JsonEscape(body)).Append(',');
            }

            sb.Append("\"queryString\":\"e_r=true\"}");
            return sb.ToString();
        }

        /// <summary>中间变换：逐字节 XOR <c>r[i &amp; 15]</c> → 左循环移位 → 前置 16 字节随机数。</summary>
        public static byte[] MidTransform(byte[] ciphertext, byte[] random16)
        {
            if (ciphertext == null)
            {
                throw new ArgumentNullException(nameof(ciphertext));
            }

            if (random16 == null || random16.Length != 16)
            {
                throw new ArgumentException("中间变换需要 16 字节随机数", nameof(random16));
            }

            var xored = new byte[ciphertext.Length];
            for (var i = 0; i < ciphertext.Length; i++)
            {
                xored[i] = (byte)(ciphertext[i] ^ random16[i & 0x0f]);
            }

            var rot = xored.Length > 0 ? (random16[0] & 0x0f) % xored.Length : 0;
            var output = new byte[16 + xored.Length];
            Buffer.BlockCopy(random16, 0, output, 0, 16);
            var tail = xored.Length - rot;
            Buffer.BlockCopy(xored, rot, output, 16, tail);
            Buffer.BlockCopy(xored, 0, output, 16 + tail, rot);
            return output;
        }

        /// <summary>X25519（RFC 7748）：<paramref name="privateKey"/> 内部 clamp，输出 32 字节小端共享密钥。</summary>
        public static byte[] X25519(byte[] privateKey, byte[] peerPublicKey)
        {
            if (privateKey == null || privateKey.Length != 32)
            {
                throw new ArgumentException("X25519 私钥必须是 32 字节", nameof(privateKey));
            }

            if (peerPublicKey == null || peerPublicKey.Length != 32)
            {
                throw new ArgumentException("X25519 对端公钥必须是 32 字节", nameof(peerPublicKey));
            }

            var k = (byte[])privateKey.Clone();
            k[0] &= 248;
            k[31] &= 127;
            k[31] |= 64;

            var u = (byte[])peerPublicKey.Clone();
            u[31] &= 127;

            var x1 = FromLittleEndian(u);
            var x2 = BigInteger.One;
            var z2 = BigInteger.Zero;
            var x3 = x1;
            var z3 = BigInteger.One;
            var swap = 0;

            for (var t = 254; t >= 0; t--)
            {
                var bit = (k[t >> 3] >> (t & 7)) & 1;
                swap ^= bit;
                if (swap == 1)
                {
                    Swap(ref x2, ref x3);
                    Swap(ref z2, ref z3);
                }

                swap = bit;

                var a = Mod(x2 + z2);
                var aa = Mod(a * a);
                var b = Mod(x2 - z2);
                var bb = Mod(b * b);
                var e = Mod(aa - bb);
                var c = Mod(x3 + z3);
                var d = Mod(x3 - z3);
                var da = Mod(d * a);
                var cb = Mod(c * b);
                x3 = Mod((da + cb) * (da + cb));
                z3 = Mod(x1 * Mod((da - cb) * (da - cb)));
                x2 = Mod(aa * bb);
                z2 = Mod(e * (aa + A24 * e));
            }

            if (swap == 1)
            {
                Swap(ref x2, ref x3);
                Swap(ref z2, ref z3);
            }

            return ToLittleEndian32(Mod(x2 * BigInteger.ModPow(z2, P - 2, P)));
        }

        /// <summary>由私钥推出公钥（标量乘基准点）。</summary>
        public static byte[] X25519PublicFromPrivate(byte[] privateKey) => X25519(privateKey, BasePoint);

        /// <summary>
        /// HKDF-SHA256 的手写单块形态：salt 全 0，info = 临时公钥 ‖ <c>0x01</c>，只取前 16 字节。
        /// </summary>
        public static byte[] DeriveAesKey(byte[] sharedSecret, byte[] ephemeralPublicKey)
        {
            if (sharedSecret == null)
            {
                throw new ArgumentNullException(nameof(sharedSecret));
            }

            if (ephemeralPublicKey == null)
            {
                throw new ArgumentNullException(nameof(ephemeralPublicKey));
            }

            byte[] prk;
            using (var hmac = new HMACSHA256(new byte[32]))
            {
                prk = hmac.ComputeHash(sharedSecret.Length > 0 ? sharedSecret : new byte[32]);
            }

            using (var hmac = new HMACSHA256(prk))
            {
                var info = new byte[ephemeralPublicKey.Length + 1];
                Buffer.BlockCopy(ephemeralPublicKey, 0, info, 0, ephemeralPublicKey.Length);
                info[info.Length - 1] = 1;
                var okm = hmac.ComputeHash(info);
                var aesKey = new byte[16];
                Buffer.BlockCopy(okm, 0, aesKey, 0, 16);
                return aesKey;
            }
        }

        /// <summary>
        /// <c>S = ephPub(32) ‖ iv(12) ‖ AES-128-GCM(明文) ‖ tag(16)</c>，
        /// 明文是 <c>base64(dynamicKey)|os|sk</c>。
        /// </summary>
        public static byte[] EncryptS(
            byte[] dynamicKey,
            string publicKeyBase64,
            string? sk,
            string os,
            byte[] ephemeralPrivate,
            byte[] iv)
        {
            if (dynamicKey == null)
            {
                throw new ArgumentNullException(nameof(dynamicKey));
            }

            if (string.IsNullOrEmpty(publicKeyBase64))
            {
                throw new ArgumentException("缺少服务端公钥", nameof(publicKeyBase64));
            }

            var ephemeralPublic = X25519PublicFromPrivate(ephemeralPrivate);
            var shared = X25519(ephemeralPrivate, Convert.FromBase64String(publicKeyBase64));
            var aesKey = DeriveAesKey(shared, ephemeralPublic);

            var plaintext = Encoding.UTF8.GetBytes(
                Convert.ToBase64String(dynamicKey) + "|" + os + "|" + (sk ?? string.Empty));
            var ciphertext = ManagedAesGcm.Encrypt(aesKey, iv, plaintext, out var tag);

            var s = new byte[ephemeralPublic.Length + iv.Length + ciphertext.Length + tag.Length];
            Buffer.BlockCopy(ephemeralPublic, 0, s, 0, ephemeralPublic.Length);
            Buffer.BlockCopy(iv, 0, s, ephemeralPublic.Length, iv.Length);
            Buffer.BlockCopy(ciphertext, 0, s, ephemeralPublic.Length + iv.Length, ciphertext.Length);
            Buffer.BlockCopy(tag, 0, s, ephemeralPublic.Length + iv.Length + ciphertext.Length, tag.Length);
            return s;
        }

        /// <summary>
        /// 组装一次业务请求的 <c>C</c> / <c>S</c> / <c>R</c> / body。有会话时用
        /// <c>x-encr-sskey</c> 的字节当动态密钥（32 个 ASCII 字符 → AES-256，UTF-8 编码与
        /// 参考实现 <c>Buffer.from(key)</c> 同形），并把 <c>ssid</c> 写进 <c>R</c>。
        /// </summary>
        public static XeapiRequest EncryptRequest(
            IEnumerable<KeyValuePair<string, string>> pairs,
            XeapiKeyState keyState,
            XeapiRandom random,
            XeapiSession? session = null,
            string os = "android")
        {
            if (keyState == null)
            {
                throw new ArgumentNullException(nameof(keyState));
            }

            if (random == null)
            {
                throw new ArgumentNullException(nameof(random));
            }

            var sessionKey = session != null && session.Key.Length > 0 ? Encoding.UTF8.GetBytes(session.Key) : null;
            if (sessionKey != null && sessionKey.Length != 16 && sessionKey.Length != 24 && sessionKey.Length != 32)
            {
                throw new ArgumentException(
                    $"会话密钥长度不是合法的 AES 密钥长度（实际 {sessionKey.Length}）", nameof(session));
            }

            var dynamicKey = sessionKey ?? random.DynamicKey;
            var plaintext = BuildPlaintext(pairs);
            var inner = AesEcbEncrypt(StaticKey, Encoding.UTF8.GetBytes(plaintext));
            var c = ToBase64Url(AesEcbEncrypt(dynamicKey, MidTransform(inner, random.MidRandom)));
            var s = Convert.ToBase64String(
                EncryptS(dynamicKey, keyState.PublicKey, keyState.Sk, os, random.EphemeralPrivate, random.Iv));
            var r = Convert.ToBase64String(AesEcbEncrypt(
                StaticKey,
                Encoding.UTF8.GetBytes(keyState.Version + "|" + (session?.Id ?? string.Empty))));

            var body = FormEncode(new[]
            {
                new KeyValuePair<string, string>("C", c),
                new KeyValuePair<string, string>("S", s),
                new KeyValuePair<string, string>("R", r),
            });

            return new XeapiRequest(plaintext, c, s, r, body);
        }

        /// <summary>
        /// 响应解密：AES-128-ECB（与 eapi 同一把 key），结果以 <c>1f 8b</c> 开头时再 gunzip。
        /// 解不开时抛 <see cref="CryptographicException"/>；网络层用
        /// <see cref="TryDecryptResponse"/> 以便区分「密钥失效」与「业务错误」。
        /// </summary>
        public static string DecryptResponse(byte[] body)
        {
            if (!TryDecryptResponse(body, out var plaintext))
            {
                throw new CryptographicException("xeapi 响应不是合法的 AES-128-ECB 密文");
            }

            return plaintext;
        }

        /// <summary>响应解密的可失败形态。</summary>
        public static bool TryDecryptResponse(byte[]? body, out string plaintext)
        {
            plaintext = string.Empty;
            if (body == null || body.Length == 0 || body.Length % 16 != 0)
            {
                return false;
            }

            byte[] decrypted;
            try
            {
                decrypted = AesEcbDecrypt(EapiCrypto.AesKey, body);
            }
            catch (CryptographicException)
            {
                return false;
            }

            if (decrypted.Length >= 2 && decrypted[0] == 0x1f && decrypted[1] == 0x8b)
            {
                try
                {
                    decrypted = Gunzip(decrypted);
                }
                catch (Exception ex) when (ex is InvalidDataException || ex is IOException)
                {
                    return false;
                }
            }

            plaintext = Encoding.UTF8.GetString(decrypted);
            return true;
        }

        private static byte[] Gunzip(byte[] data)
        {
            using (var input = new MemoryStream(data))
            using (var gzip = new GZipStream(input, CompressionMode.Decompress))
            using (var output = new MemoryStream())
            {
                gzip.CopyTo(output);
                return output.ToArray();
            }
        }

        private static byte[] AesEcbEncrypt(byte[] key, byte[] data)
        {
            using (var aes = Aes.Create())
            {
                aes.Key = key;
                aes.Mode = CipherMode.ECB;
                aes.Padding = PaddingMode.PKCS7;
                using (var encryptor = aes.CreateEncryptor())
                {
                    return encryptor.TransformFinalBlock(data, 0, data.Length);
                }
            }
        }

        private static byte[] AesEcbDecrypt(byte[] key, byte[] data)
        {
            using (var aes = Aes.Create())
            {
                aes.Key = key;
                aes.Mode = CipherMode.ECB;
                aes.Padding = PaddingMode.PKCS7;
                using (var decryptor = aes.CreateDecryptor())
                {
                    return decryptor.TransformFinalBlock(data, 0, data.Length);
                }
            }
        }

        /// <summary>base64url 且不补 <c>=</c>（协议对 <c>C</c> 的要求，夹具专门覆盖）。</summary>
        private static string ToBase64Url(byte[] data) =>
            Convert.ToBase64String(data).TrimEnd('=').Replace('+', '-').Replace('/', '_');

        private static BigInteger Mod(BigInteger value)
        {
            var result = value % P;
            return result.Sign < 0 ? result + P : result;
        }

        private static void Swap(ref BigInteger a, ref BigInteger b)
        {
            var t = a;
            a = b;
            b = t;
        }

        private static BigInteger FromLittleEndian(byte[] bytes)
        {
            // BigInteger(byte[]) 按有符号小端解释，末尾补一个 0 保证非负。
            var padded = new byte[bytes.Length + 1];
            Buffer.BlockCopy(bytes, 0, padded, 0, bytes.Length);
            return new BigInteger(padded);
        }

        private static byte[] ToLittleEndian32(BigInteger value)
        {
            var bytes = value.ToByteArray();
            var result = new byte[32];
            Buffer.BlockCopy(bytes, 0, result, 0, Math.Min(32, bytes.Length));
            return result;
        }

        private static byte[] HexToBytes(string hex)
        {
            var bytes = new byte[hex.Length / 2];
            for (var i = 0; i < bytes.Length; i++)
            {
                bytes[i] = byte.Parse(hex.Substring(i * 2, 2), NumberStyles.HexNumber, CultureInfo.InvariantCulture);
            }

            return bytes;
        }

        /// <summary>只转义 JSON 字符串必须转义的字符，其余原样（与 JSON.stringify 对 ASCII 的行为一致）。</summary>
        private static string JsonEscape(string value)
        {
            var sb = new StringBuilder(value.Length + 2);
            sb.Append('"');
            foreach (var c in value)
            {
                switch (c)
                {
                    case '"':
                        sb.Append("\\\"");
                        break;
                    case '\\':
                        sb.Append("\\\\");
                        break;
                    case '\b':
                        sb.Append("\\b");
                        break;
                    case '\f':
                        sb.Append("\\f");
                        break;
                    case '\n':
                        sb.Append("\\n");
                        break;
                    case '\r':
                        sb.Append("\\r");
                        break;
                    case '\t':
                        sb.Append("\\t");
                        break;
                    default:
                        if (c < ' ')
                        {
                            sb.Append("\\u").Append(((int)c).ToString("x4", CultureInfo.InvariantCulture));
                        }
                        else
                        {
                            sb.Append(c);
                        }

                        break;
                }
            }

            sb.Append('"');
            return sb.ToString();
        }
    }
}
