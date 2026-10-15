package com.takahashirinta.ncrust.walkman.crypto

import java.math.BigInteger
import java.security.SecureRandom

/**
 * weapi 加密（`spec/fixtures/crypto/weapi.json`）。
 *
 * 两个易错点，夹具就是盯它们的：
 *  - `params` 是**两层** AES-128-CBC：先用固定 presetKey，再用随机 secKey。只做一层服务端解不开。
 *  - `encSecKey` 是**原始 RSA**：把 secKey 反转后当大整数做 `modPow`，输出 256 位小写 hex ——
 *    没有 PKCS#1 填充，也不是 base64。
 *
 * 第三处（夹具实测抓出来的，`spec/fixtures/crypto/weapi.json`）：**两层之间的中间值是 base64 文本，
 * 不是内层密文的原始字节**。第二层加密的输入是内层结果的 base64 字符串的 UTF-8 字节。
 * 两处依据都指向这个写法：参考实现 `spec/tools/ncm-client.mjs:118`
 * （`cbc(secKey, Buffer.from(cbc(PRESET, json).toString('base64')))`）与 Android
 * `WeapiCrypto.aesCbcEncrypt`（返回 `String`，内层结果被当字符串喂给外层）。
 * 直接喂原始字节会得到一个**长度对、内容错**的 params —— 服务端解不开，本地却看不出问题，
 * 所以这里必须按夹具的字节来。
 *
 * secKey 真实实现是随机 16 字符；这里把随机源作为参数暴露，夹具用固定值才能复现。
 */
object Weapi {

    private const val AES_IV = "0102030405060708"
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"

    /** 官方 weapi 公钥：modulus + exponent `0x10001`（与三端其它实现同一份常量）。 */
    private const val PUBLIC_KEY_MODULUS_HEX =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b7251" +
            "52b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ec" +
            "bda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d81" +
            "3cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val PUBLIC_KEY_EXP_HEX = "010001"

    private const val SEC_KEY_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    private val MODULUS: BigInteger by lazy { BigInteger(1, Hex.decode(PUBLIC_KEY_MODULUS_HEX)) }
    private val EXPONENT: BigInteger by lazy { BigInteger(1, Hex.decode(PUBLIC_KEY_EXP_HEX)) }

    /** 生产用：随机 16 字符 secKey。 */
    fun randomSecKey(random: SecureRandom): String {
        val sb = StringBuilder(16)
        repeat(16) { sb.append(SEC_KEY_CHARS[random.nextInt(SEC_KEY_CHARS.length)]) }
        return sb.toString()
    }

    /** 返回 `params`（base64）与 `encSecKey`（256 位小写 hex）。 */
    fun encryptParams(payloadJson: String, secKey: String): Pair<String, String> {
        require(secKey.length == 16) { "weapi secKey must be 16 chars, got ${secKey.length}" }
        // 内层结果先转 base64 文本，外层加密的是这段文本的字节（见类注释第三点）。
        val once = Base64.encode(
            Aes.cbcEncrypt(PRESET_KEY.toByteArray(Charsets.UTF_8), AES_IV.toByteArray(Charsets.UTF_8), payloadJson.toByteArray(Charsets.UTF_8))
        )
        val twice = Aes.cbcEncrypt(
            secKey.toByteArray(Charsets.UTF_8),
            AES_IV.toByteArray(Charsets.UTF_8),
            once.toByteArray(Charsets.UTF_8)
        )
        return Base64.encode(twice) to rsaEncryptHex(secKey)
    }

    /** `reverse(secKey)` 的 ASCII 字节当大整数 → `m^e mod n` → 补足 256 位小写 hex。 */
    private fun rsaEncryptHex(secKey: String): String {
        val message = BigInteger(1, secKey.reversed().toByteArray(Charsets.UTF_8))
        return message.modPow(EXPONENT, MODULUS).toString(16).padStart(256, '0')
    }
}
