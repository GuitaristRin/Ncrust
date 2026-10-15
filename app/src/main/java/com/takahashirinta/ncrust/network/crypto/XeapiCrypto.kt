package com.takahashirinta.ncrust.network.crypto

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * xeapi —— 官方 Android 9.x 的请求加密层，收藏写入（`/api/v1/radio/like`）走的通道。
 * 协议全文：`spec/api/xeapi.md`；参考实现：`spec/tools/xeapi.mjs`（本文件逐行对照它移植）。
 *
 * 为什么自带 base64 而不用平台库：
 *  - `java.util.Base64` 要 API 26，minSdk 是 24；
 *  - `android.util.Base64` 在 JVM 单测里是空桩（android.jar 的 "not mocked"），
 *    于是恰好就没法拿夹具逐字节对照——而 xeapi 的易错点全在编码细节上。
 * 自己写 ~60 行编解码（标准带填充 + url-safe 不带填充），同一份实现既跑设备也跑单测。
 *
 * 所有随机量（动态密钥 / mid 随机数 / X25519 临时私钥 / GCM IV）都由调用方经 [Rng] 注入：
 * 生产用 [randomRng]（SecureRandom），单测用夹具里的固定值，两边走同一条码路。
 */
object XeapiCrypto {
    /** AES-256-ECB 的静态 key：公钥解密、明文信封内层、R。 */
    private val STATIC_KEY = hexToBytes("ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84")

    /**
     * 公钥接口请求签名的 HMAC key。**是这串字符的 ASCII 字节**，不做 base64 解码
     * （上游 api-enhanced 如此，夹具 `xeapi.sign.key-request` 锁住这一点）。
     */
    private const val SIGN_KEY = "mUHCwVNWJbunMqAHf5MImuirT6plvs6VSFW62MGHstFQxhBGdEoIhLItH3djc4+FB/OKty3+lL2rGeoFBpVe5g=="

    /** 响应解密用的 AES-128 key，与 eapi 同一把。 */
    private val EAPI_KEY = "e82ckenh8dichen8".toByteArray(Charsets.UTF_8)

    private const val B64_STD = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val B64_URL = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    // 不用 BigInteger.TWO：它在 Android 上要 API 31（无 core library desugaring），minSdk 是 24。
    private val P25519 = BigInteger.valueOf(2).pow(255).subtract(BigInteger.valueOf(19))

    /** RFC 7748 的 a24 = (486662 - 2) / 4。 */
    private val A24_25519 = BigInteger.valueOf(121665)

    /** 公钥状态（公钥接口响应解出来的四个字段）。 */
    data class KeyState(
        val publicKey: String,
        val version: String,
        val sk: String,
        val nextUpdateTime: Long = 0L
    )

    /** 响应头 `x-encr-ssid` / `x-encr-sskey`。 */
    data class Session(val id: String, val key: String)

    /** 一次请求要用到的全部随机量。 */
    class Rng(
        val dynamicKey: ByteArray,
        val midRandom: ByteArray,
        val ephemeralPrivate: ByteArray,
        val iv: ByteArray
    )

    /** [encryptRequest] 的结果。[body] 是最终要 POST 的表单文本。 */
    data class EncryptedRequest(
        val plaintext: String,
        val c: String,
        val s: String,
        val r: String,
        val body: String
    )

    /** 生产随机量。 */
    fun randomRng(random: SecureRandom): Rng = Rng(
        dynamicKey = ByteArray(16).also(random::nextBytes),
        midRandom = ByteArray(16).also(random::nextBytes),
        ephemeralPrivate = ByteArray(32).also(random::nextBytes),
        iv = ByteArray(12).also(random::nextBytes)
    )

    // ==================== 公钥接口 ====================

    /** 公钥接口签名：`Base64(HMAC-SHA256(SIGN_KEY 的 ASCII 字节, timestamp + nonce))`。 */
    fun sign(timestamp: String, nonce: String): String =
        base64Encode(hmacSha256(SIGN_KEY.toByteArray(Charsets.UTF_8), (timestamp + nonce).toByteArray(Charsets.UTF_8)))

    /** 公钥接口 `data.encryptedData` → 公钥状态 JSON 文本（AES-256-ECB，静态 key）。 */
    fun decryptPublicKey(encryptedDataBase64: String): String =
        String(aesEcb(STATIC_KEY, base64Decode(encryptedDataBase64), decrypt = true), Charsets.UTF_8)

    // ==================== 明文信封 ====================

    /**
     * `application/x-www-form-urlencoded` 序列化（WHATWG 语义，见 xeapi.md §4）。
     * JDK 的 [URLEncoder] 与之等价：`~` → `%7E`、`*` 原样、空格 → `+`，
     * 夹具 `xeapi.plaintext.tricky` 专门盯这几个边界。
     */
    fun formEncode(pairs: List<Pair<String, String>>): String =
        pairs.joinToString("&") { (k, v) -> encodeComponent(k) + "=" + encodeComponent(v) }

    /**
     * 业务明文信封：先 `content`（业务字段的表单编码，剔除 `e_r`），再 `queryString`。
     * 字段顺序是协议的一部分——整段 JSON 文本参与加密，换序即换密文。
     *
     * 这里手拼 JSON 而不用 org.json：单测里的 org.json 是空桩，用了就没法在 JVM 上跑夹具；
     * 而 [formEncode] 的输出只含 `A-Za-z0-9*-._%+`，本来也不需要 JSON 转义。
     */
    fun buildPlaintext(pairs: List<Pair<String, String>>): String {
        val body = formEncode(pairs.filter { it.first != "e_r" })
        val content = if (body.isNotEmpty()) "\"content\":\"$body\"," else ""
        return "{$content\"queryString\":\"e_r=true\"}"
    }

    // ==================== 中间变换 ====================

    /** `mid(x)`：逐字节 XOR 随机 16 字节 → 左循环移位 `(r[0] & 15) % len` → 前缀随机 16 字节。 */
    fun midTransform(ciphertext: ByteArray, random16: ByteArray): ByteArray {
        require(random16.size == 16) { "mid random must be 16 bytes, got ${random16.size}" }
        val xored = ByteArray(ciphertext.size) { i ->
            (ciphertext[i].toInt() xor random16[i and 0x0f].toInt()).toByte()
        }
        val rot = if (xored.isEmpty()) 0 else (random16[0].toInt() and 0x0f) % xored.size
        val out = ByteArray(random16.size + xored.size)
        random16.copyInto(out, 0)
        xored.copyInto(out, 16, rot, xored.size)
        xored.copyInto(out, 16 + xored.size - rot, 0, rot)
        return out
    }

    // ==================== X25519（RFC 7748）====================

    /**
     * X25519 标量乘法（Montgomery ladder）。
     *
     * 自己实现而不是 `KeyAgreement("XDH")`：XDH 要 API 33，minSdk 是 24；RFC 7748 的
     * 算法短且自包含，用 [BigInteger] 写最省事，255 轮 modmul 在低端机上也是毫秒级。
     * clamp 在函数内做（私钥入参按原样传即可）。
     */
    fun x25519(privateKeyRaw: ByteArray, peerPublicRaw: ByteArray): ByteArray {
        require(privateKeyRaw.size == 32) { "x25519 private key must be 32 bytes" }
        require(peerPublicRaw.size == 32) { "x25519 public key must be 32 bytes" }

        val k = privateKeyRaw.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()

        val u = peerPublicRaw.copyOf()
        // RFC 7748 decodeUCoordinate：X25519 忽略 u 坐标的最高位。
        u[31] = (u[31].toInt() and 127).toByte()

        val x1 = littleEndianToBigInteger(u)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val bit = (k[t shr 3].toInt() and 0xff) ushr (t and 7) and 1
            swap = swap xor bit
            if (swap == 1) {
                var tmp = x2; x2 = x3; x3 = tmp
                tmp = z2; z2 = z3; z3 = tmp
            }
            swap = bit

            val a = x2.add(z2).mod(P25519)
            val aa = a.multiply(a).mod(P25519)
            val b = x2.subtract(z2).mod(P25519)
            val bb = b.multiply(b).mod(P25519)
            val e = aa.subtract(bb).mod(P25519)
            val c = x3.add(z3).mod(P25519)
            val d = x3.subtract(z3).mod(P25519)
            val da = d.multiply(a).mod(P25519)
            val cb = c.multiply(b).mod(P25519)

            val daPlusCb = da.add(cb).mod(P25519)
            val daMinusCb = da.subtract(cb).mod(P25519)
            x3 = daPlusCb.multiply(daPlusCb).mod(P25519)
            z3 = x1.multiply(daMinusCb.multiply(daMinusCb).mod(P25519)).mod(P25519)
            x2 = aa.multiply(bb).mod(P25519)
            z2 = e.multiply(aa.add(A24_25519.multiply(e))).mod(P25519)
        }

        // 收尾的 cswap 只写回 x2/z2：x3/z3 此后不再使用，直接读它们即可。
        if (swap == 1) {
            x2 = x3
            z2 = z3
        }

        // z2^-1 = z2^(p-2)；z2 == 0 时结果为 0（低阶点），与 RFC 一致。
        val result = x2.multiply(z2.modPow(P25519.subtract(BigInteger.valueOf(2)), P25519)).mod(P25519)
        return bigIntegerToLittleEndian(result)
    }

    /** 由 32 字节原始私钥导出 32 字节原始公钥（basepoint = 9）。 */
    fun x25519PublicFromPrivate(privateKeyRaw: ByteArray): ByteArray =
        x25519(privateKeyRaw, ByteArray(32).also { it[0] = 9 })

    /** HKDF-SHA256 的手写形态：salt 全 0、info = 临时公钥 ‖ 0x01，只取前 16 字节作 AES-128 key。 */
    fun deriveAesKey(sharedSecret: ByteArray, ephemeralPublic: ByteArray): ByteArray {
        val prk = hmacSha256(ByteArray(32), sharedSecret)
        val okm = hmacSha256(prk, ephemeralPublic + byteArrayOf(1))
        return okm.copyOf(16)
    }

    /**
     * `S = ephPub(32) ‖ iv(12) ‖ AES-128-GCM(base64(dynamicKey)|os|sk) ‖ tag(16)`。
     * JDK/Android 的 GCM `doFinal` 输出就是「密文 ‖ tag」，直接拼即可。
     */
    fun encryptS(
        dynamicKey: ByteArray,
        publicKeyBase64: String,
        sk: String,
        os: String,
        ephemeralPrivate: ByteArray,
        iv: ByteArray
    ): ByteArray {
        require(iv.size == 12) { "GCM iv must be 12 bytes, got ${iv.size}" }
        val ephemeralPublic = x25519PublicFromPrivate(ephemeralPrivate)
        val shared = x25519(ephemeralPrivate, base64Decode(publicKeyBase64))
        val aesKey = deriveAesKey(shared, ephemeralPublic)
        val plaintext = "${base64Encode(dynamicKey)}|$os|$sk".toByteArray(Charsets.UTF_8)

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(aesKey, "AES"), GCMParameterSpec(128, iv))
        val sealed = cipher.doFinal(plaintext)

        val out = ByteArray(ephemeralPublic.size + iv.size + sealed.size)
        ephemeralPublic.copyInto(out, 0)
        iv.copyInto(out, 32)
        sealed.copyInto(out, 44)
        return out
    }

    // ==================== 业务请求 ====================

    /**
     * 组装一次 xeapi 请求体。
     *
     * `session` 非空即「会话模式」：动态密钥换成 `x-encr-sskey` 的 ASCII 字节（32 字节 → AES-256），
     * 且 `R` 的会话段写 `ssid`（服务端据此认出这是同一条会话）。
     */
    fun encryptRequest(
        pairs: List<Pair<String, String>>,
        keyState: KeyState,
        os: String,
        session: Session?,
        rng: Rng
    ): EncryptedRequest {
        val sessionKey = session?.key?.toByteArray(Charsets.UTF_8)
        val dynamicKey = sessionKey ?: rng.dynamicKey
        val plaintext = buildPlaintext(pairs)
        val inner = aesEcb(STATIC_KEY, plaintext.toByteArray(Charsets.UTF_8), decrypt = false)
        val c = base64Encode(aesEcb(dynamicKey, midTransform(inner, rng.midRandom), decrypt = false), urlSafe = true, pad = false)
        val s = base64Encode(
            encryptS(
                dynamicKey = dynamicKey,
                publicKeyBase64 = keyState.publicKey,
                sk = keyState.sk,
                os = os,
                ephemeralPrivate = rng.ephemeralPrivate,
                iv = rng.iv
            )
        )
        val r = base64Encode(
            aesEcb(STATIC_KEY, "${keyState.version}|${session?.id ?: ""}".toByteArray(Charsets.UTF_8), decrypt = false)
        )
        return EncryptedRequest(
            plaintext = plaintext,
            c = c,
            s = s,
            r = r,
            body = formEncode(listOf("C" to c, "S" to s, "R" to r))
        )
    }

    /** 响应解密：AES-128-ECB（eapi key）；结果以 `1f 8b` 开头时是 gzip，再解压。 */
    fun decryptResponse(bytes: ByteArray): String {
        val decrypted = aesEcb(EAPI_KEY, bytes, decrypt = true)
        val plain = if (decrypted.size >= 2 && decrypted[0] == 0x1f.toByte() && decrypted[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(decrypted)).use { it.readBytes() }
        } else {
            decrypted
        }
        return String(plain, Charsets.UTF_8)
    }

    // ==================== base64 ====================

    /** 标准 base64（默认带 `=`）或 url-safe 无填充——`C` 用后者，`S`/`R` 用前者。 */
    fun base64Encode(data: ByteArray, urlSafe: Boolean = false, pad: Boolean = true): String {
        val table = if (urlSafe) B64_URL else B64_STD
        val sb = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 2 < data.size) {
            val n = ((data[i].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8) or (data[i + 2].toInt() and 0xff)
            sb.append(table[n ushr 18 and 63]).append(table[n ushr 12 and 63])
                .append(table[n ushr 6 and 63]).append(table[n and 63])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xff) shl 16
                sb.append(table[n ushr 18 and 63]).append(table[n ushr 12 and 63])
                if (pad) sb.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8)
                sb.append(table[n ushr 18 and 63]).append(table[n ushr 12 and 63]).append(table[n ushr 6 and 63])
                if (pad) sb.append('=')
            }
        }
        return sb.toString()
    }

    /** 解码：两种字母表都认，`=` 与空白忽略（服务端两种写法混用，没必要让调用方区分）。 */
    fun base64Decode(text: String): ByteArray {
        val out = ByteArrayOutputStream(text.length * 3 / 4 + 3)
        var buffer = 0
        var bits = 0
        for (ch in text) {
            val value = when (ch) {
                in 'A'..'Z' -> ch - 'A'
                in 'a'..'z' -> ch - 'a' + 26
                in '0'..'9' -> ch - '0' + 52
                '+', '-' -> 62
                '/', '_' -> 63
                '=', '\n', '\r', ' ', '\t' -> continue
                else -> throw IllegalArgumentException("invalid base64 character '$ch'")
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write(buffer ushr bits and 0xff)
            }
        }
        return out.toByteArray()
    }

    // ==================== 内部工具 ====================

    private fun encodeComponent(s: String): String = URLEncoder.encode(s, Charsets.UTF_8.name())

    private fun aesEcb(key: ByteArray, data: ByteArray, decrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(if (decrypt) Cipher.DECRYPT_MODE else Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        return cipher.doFinal(data)
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun littleEndianToBigInteger(bytes: ByteArray): BigInteger {
        val bigEndian = ByteArray(bytes.size)
        for (i in bytes.indices) bigEndian[i] = bytes[bytes.size - 1 - i]
        return BigInteger(1, bigEndian)
    }

    private fun bigIntegerToLittleEndian(value: BigInteger): ByteArray {
        val bigEndian = value.toByteArray()
        val out = ByteArray(32)
        val count = min(bigEndian.size, 32)
        for (i in 0 until count) out[i] = bigEndian[bigEndian.size - 1 - i]
        return out
    }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = ((Character.digit(hex[i * 2], 16) shl 4) or Character.digit(hex[i * 2 + 1], 16)).toByte()
        }
        return out
    }
}
