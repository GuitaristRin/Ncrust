using System;
using System.Security.Cryptography;

namespace Ncrust.Core.Net.Crypto
{
    /// <summary>
    /// 纯托管的 AES-128-GCM **加密**（只做加密：xeapi 只用它来封 <c>S</c>，不需要解密响应）。
    ///
    /// 为什么不用框架的 <c>AesGcm</c>：netstandard2.0 没有这个类型（.NET Standard 2.1 才加入），
    /// 而 Core 必须留在 netstandard2.0 才能被 UWP 项目直接引用。AES 块运算仍借
    /// <see cref="Aes"/> 的 ECB 模式实现，CTR 与 GHASH 按 NIST SP 800-38D 自己写，
    /// 因此只有这几段需要为正确性负责；测试用 net9 的 <c>AesGcm</c> 逐字节交叉比对。
    ///
    /// 只支持 xeapi 用到的形态：96 位 IV、无 AAD、128 位 tag。
    /// </summary>
    internal static class ManagedAesGcm
    {
        public const int TagSize = 16;

        private static readonly byte[] ZeroBlock = new byte[16];

        /// <summary>GCM 加密。<paramref name="tag"/> 为 16 字节认证标签。</summary>
        public static byte[] Encrypt(byte[] key, byte[] nonce, byte[] plaintext, out byte[] tag)
        {
            if (key == null)
            {
                throw new ArgumentNullException(nameof(key));
            }

            if (key.Length != 16)
            {
                throw new ArgumentException($"AES-128-GCM 需要 16 字节密钥（实际 {key.Length}）", nameof(key));
            }

            if (nonce == null || nonce.Length != 12)
            {
                throw new ArgumentException("本实现只支持 12 字节 IV", nameof(nonce));
            }

            if (plaintext == null)
            {
                throw new ArgumentNullException(nameof(plaintext));
            }

            using (var aes = Aes.Create())
            {
                aes.Key = key;
                aes.Mode = CipherMode.ECB;
                aes.Padding = PaddingMode.None;
                using (var encryptor = aes.CreateEncryptor())
                {
                    // H = AES_K(0^128)；J0 = IV ‖ 0x00000001（96 位 IV 的固定形态）。
                    var hashKey = new byte[16];
                    EncryptBlock(encryptor, ZeroBlock, 0, hashKey, 0);
                    var counter = new byte[16];
                    Buffer.BlockCopy(nonce, 0, counter, 0, 12);
                    counter[15] = 1;

                    var ciphertext = new byte[plaintext.Length];
                    var keystream = new byte[16];
                    for (var offset = 0; offset < plaintext.Length; offset += 16)
                    {
                        Increment32(counter);
                        EncryptBlock(encryptor, counter, 0, keystream, 0);
                        var count = Math.Min(16, plaintext.Length - offset);
                        for (var i = 0; i < count; i++)
                        {
                            ciphertext[offset + i] = (byte)(plaintext[offset + i] ^ keystream[i]);
                        }
                    }

                    // tag = AES_K(J0) ⊕ GHASH_H(C ‖ len(C))（无 AAD，长度块的高 64 位为 0）。
                    var ghash = GHash(hashKey, ciphertext);
                    var encryptedJ0 = new byte[16];
                    var j0 = new byte[16];
                    Buffer.BlockCopy(nonce, 0, j0, 0, 12);
                    j0[15] = 1;
                    EncryptBlock(encryptor, j0, 0, encryptedJ0, 0);

                    tag = new byte[TagSize];
                    for (var i = 0; i < TagSize; i++)
                    {
                        tag[i] = (byte)(encryptedJ0[i] ^ ghash[i]);
                    }

                    return ciphertext;
                }
            }
        }

        private static void Increment32(byte[] counter)
        {
            // GCM 的 inc32：只在低 32 位上进位，不回卷到高 96 位。
            for (var i = 15; i >= 12; i--)
            {
                if (++counter[i] != 0)
                {
                    break;
                }
            }
        }

        private static byte[] GHash(byte[] hashKey, byte[] ciphertext)
        {
            var y = new byte[16];
            var block = new byte[16];

            for (var offset = 0; offset < ciphertext.Length; offset += 16)
            {
                Array.Clear(block, 0, 16);
                var count = Math.Min(16, ciphertext.Length - offset);
                Buffer.BlockCopy(ciphertext, offset, block, 0, count);
                Xor(y, block);
                y = Multiply(y, hashKey);
            }

            Array.Clear(block, 0, 16);
            var bitLength = (ulong)ciphertext.Length * 8UL;
            for (var i = 0; i < 8; i++)
            {
                block[8 + i] = (byte)(bitLength >> (56 - 8 * i));
            }

            Xor(y, block);
            return Multiply(y, hashKey);
        }

        /// <summary>GF(2^128) 乘法，R = 11100001 ‖ 0^120（SP 800-38D 算法 1）。</summary>
        private static byte[] Multiply(byte[] x, byte[] y)
        {
            var z = new byte[16];
            var v = (byte[])y.Clone();
            for (var i = 0; i < 128; i++)
            {
                if ((x[i >> 3] & (0x80 >> (i & 7))) != 0)
                {
                    Xor(z, v);
                }

                var lsb = (v[15] & 1) != 0;
                for (var j = 15; j > 0; j--)
                {
                    v[j] = (byte)((v[j] >> 1) | ((v[j - 1] & 1) << 7));
                }

                v[0] >>= 1;
                if (lsb)
                {
                    v[0] ^= 0xe1;
                }
            }

            return z;
        }

        private static void Xor(byte[] target, byte[] value)
        {
            for (var i = 0; i < 16; i++)
            {
                target[i] ^= value[i];
            }
        }

        private static void EncryptBlock(ICryptoTransform encryptor, byte[] input, int inputOffset, byte[] output, int outputOffset) =>
            encryptor.TransformBlock(input, inputOffset, 16, output, outputOffset);
    }
}
