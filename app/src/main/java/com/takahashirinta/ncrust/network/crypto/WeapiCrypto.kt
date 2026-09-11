package com.takahashirinta.ncrust.network.crypto

import android.util.Base64
import java.math.BigInteger
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * weapi 加密方案（与网易官方 WeAPI 一致，参照 NeteaseCloudMusicApi 的 crypto.js）。
 *
 * 网易受保护接口（收藏单曲列表、扫码登录等）走 weapi：
 *   1. 随机 16 位 secKey；
 *   2. params = AES-128-CBC(AES-128-CBC(明文 JSON, presetKey), secKey) → base64；
 *   3. encSecKey = rawRSA(reverse(secKey)) → 256 hex（**无填充、非 base64**）；
 *   4. 表单 POST `params` + `encSecKey` 到对应 /weapi/ 路径。
 *
 * 注意两个易错点：
 *  - params 是**两层** AES：先固定 presetKey，再随机 secKey。只做一层服务端解不开。
 *  - encSecKey 是原始 RSA（secKey 反转后当大整数做 modPow）输出 256 位十六进制，
 *    不是 PKCS1 填充后 base64。
 */
object WeapiCrypto {
    private const val AES_IV = "0102030405060708"
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"

    // 官方 weapi 公钥（modulus + exponent 0x10001）。
    private const val PUBLIC_KEY_MODULUS_HEX =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b7251" +
        "52b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ec" +
        "bda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d81" +
        "3cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val PUBLIC_KEY_EXP_HEX = "010001"

    private const val SEC_KEY_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    private val rng = Random()

    /** 对 JSON 明文生成 weapi 的 params + encSecKey 表单字段。 */
    fun encryptParams(json: String): Pair<String, String> {
        val secKey = randomSecKey()
        val params = aesCbcEncrypt(aesCbcEncrypt(json, PRESET_KEY), secKey)
        return params to rsaEncryptHex(secKey)
    }

    private fun randomSecKey(): String {
        val sb = StringBuilder(16)
        repeat(16) { sb.append(SEC_KEY_CHARS[rng.nextInt(SEC_KEY_CHARS.length)]) }
        return sb.toString()
    }

    private fun aesCbcEncrypt(data: String, key: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(AES_IV.toByteArray(Charsets.UTF_8))
        )
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /** secKey 字符串反转 → big-endian 字节 → m^e mod n → 256 位小写 hex。 */
    private fun rsaEncryptHex(secKey: String): String {
        val modulus = BigInteger(1, hexToBytes(PUBLIC_KEY_MODULUS_HEX))
        val exponent = BigInteger(1, hexToBytes(PUBLIC_KEY_EXP_HEX))
        val message = BigInteger(1, secKey.reversed().toByteArray(Charsets.UTF_8))
        return message.modPow(exponent, modulus).toString(16).padStart(256, '0')
    }

    private fun hexToBytes(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex length" }
        return ByteArray(hex.length / 2) { i ->
            ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
    }
}
