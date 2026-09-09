package com.takahashirinta.ncrust.network.crypto

import android.util.Base64
import java.math.BigInteger
import java.security.KeyFactory
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAPublicKeySpec
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * weapi 加密方案（与网易官方 WeAPI 一致，参照 NeteaseCloudMusicApi 的 crypto.js）。
 *
 * 网易部分受保护接口（收藏单曲列表、收藏专辑等）走 weapi：
 *   1. 随机 16 位 secKey；
 *   2. params = AES-128-CBC(明文 JSON, key=secKey, iv="0102030405060708") → base64；
 *   3. encSecKey = RSA-1024-PKCS1(逆序后的 secKey, 官方公钥) → base64；
 *   4. 表单 POST `params` + `encSecKey` 到对应 /api/ 路径。
 */
object WeapiCrypto {
    private const val AES_IV = "0102030405060708"
    // 标准 weapi 第一重加密的固定密钥(crypto.js: const e = "0CoJUm6Qyw8W8jud")
    private const val FIXED_SECRET_KEY = "0CoJUm6Qyw8W8jud"

    /** 登录等场景要的 MD5 hex(如密码/验证码口令)。平台无关, 小写 hex。 */
    fun md5Hex(input: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5")
        return digest.digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    // 官方 weapi 公钥（modulus + exponent 0x10001）。
    private const val PUBLIC_KEY_MODULUS_HEX =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b7251" +
        "52b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ec" +
        "bda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d81" +
        "3cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val PUBLIC_KEY_EXP_HEX = "010001"

    private const val SEC_KEY_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    private val rng = Random()

    /**
     * 对 JSON 明文生成 weapi 的 params + encSecKey 表单字段。
     * 标准 weapi 是双重加密(crypto.js): 先用固定密钥 0CoJUm6Qyw8W8jud 加密一次,
     * 再用随机 secKey 加密一次——只加密一次服务器解出来的就不是 JSON。
     */
    fun encryptParams(json: String): Pair<String, String> {
        val secKey = randomSecKey()
        val pass1 = aesCbcEncryptToBytes(json, FIXED_SECRET_KEY.toByteArray(Charsets.UTF_8))
        val params = Base64.encodeToString(
            aesCbcEncryptBytes(pass1, secKey.toByteArray(Charsets.UTF_8)),
            Base64.NO_WRAP
        )
        val encSecKey = rsaEncrypt(secKey.reversed().toByteArray(Charsets.UTF_8))
        return params to encSecKey
    }

    private fun randomSecKey(): String {
        val sb = StringBuilder(16)
        repeat(16) { sb.append(SEC_KEY_CHARS[rng.nextInt(SEC_KEY_CHARS.length)]) }
        return sb.toString()
    }

    private fun aesCbcEncryptToBytes(data: String, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
        )
        return cipher.doFinal(data.toByteArray(Charsets.UTF_8))
    }

    private fun aesCbcEncryptBytes(data: ByteArray, key: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
        )
        return cipher.doFinal(data)
    }

    /** 官方要求 encSecKey 为 hex 编码(256 字符), 不是 base64。 */
    private fun rsaEncrypt(data: ByteArray): String {
        val modulus = BigInteger(1, hexToBytes(PUBLIC_KEY_MODULUS_HEX))
        val exponent = BigInteger(1, hexToBytes(PUBLIC_KEY_EXP_HEX))
        val pubKey: RSAPublicKey =
            KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(modulus, exponent)) as RSAPublicKey
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, pubKey)
        return cipher.doFinal(data).joinToString("") { "%02x".format(it) }
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }
}
