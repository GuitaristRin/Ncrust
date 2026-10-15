package com.takahashirinta.ncrust.walkman.crypto

import com.takahashirinta.ncrust.walkman.net.Form
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * xeapi 加密（官方 Android 9.x 的请求层）。协议全文 `spec/api/xeapi.md`，
 * 夹具 `spec/fixtures/crypto/xeapi.json`，参考实现 `spec/tools/xeapi.mjs`。
 *
 * 三处最容易写错、夹具逐条盯着的：
 *  - **`C` 用 base64url 且不补 `=`，`S`/`R` 用标准 base64 且补 `=`**。混用会得到一个
 *    "看起来像 base64" 的串，服务端直接判非法，本地却看不出问题。
 *  - **HKDF 只扩展一块**：`PRK = HMAC(0^32, shared)`，`key = HMAC(PRK, ephPub ‖ 0x01)[0..16]`。
 *  - **`mid(x)` 的旋转量依赖随机数**：`rot = (r[0] & 15) % len`，且输出是 `r ‖ 旋转后的 x`。
 *
 * 这里的随机量全部由参数注入（[XeapiRng]），生产用 [XeapiRng.random]，夹具用固定值 ——
 * 这样同一条码路既能复现夹具，也能真跑。
 */
object Xeapi {

    /** AES-256-ECB 的静态钥匙：公钥解密、`C` 的内层、`R` 都用它。 */
    private val STATIC_KEY = Hex.decode("ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84")

    /** HMAC-SHA256 的 key。**取这串字符的 ASCII 字节**，不做 base64 解码（`xeapi.md` §1 特别注明）。 */
    private const val SIGN_KEY =
        "mUHCwVNWJbunMqAHf5MImuirT6plvs6VSFW62MGHstFQxhBGdEoIhLItH3djc4+FB/OKty3+lL2rGeoFBpVe5g=="

    /** 响应解密用的 AES-128 钥匙，与 eapi 同一把。 */
    private val EAPI_KEY = "e82ckenh8dichen8".toByteArray(Charsets.UTF_8)

    /** 公钥接口的签名：`Base64(HMAC-SHA256(SIGN_KEY, timestamp + nonce))`。 */
    fun sign(timestamp: String, nonce: String): String =
        Base64.encode(hmacSha256(SIGN_KEY.toByteArray(Charsets.UTF_8), (timestamp + nonce).toByteArray(Charsets.UTF_8)))

    /** 公钥接口响应 `data.encryptedData` → 公钥状态 JSON 文本。 */
    fun decryptPublicKey(encryptedDataBase64: String): String =
        String(Aes.ecbDecrypt(STATIC_KEY, Base64.decode(encryptedDataBase64)), Charsets.UTF_8)

    /**
     * 明文信封 `P`：`{"content":"<剔除 e_r 后的表单体>","queryString":"e_r=true"}`。
     *
     * 字段顺序是协议的一部分（整段 JSON 文本参与加密），所以这里手拼而不是走序列化器。
     * 不用转义：`content` 是表单编码的产物，只含 `A–Z a–z 0–9 + % * - . _`，不可能出现
     * `"` 或 `\`。无业务字段时按规范省略 `content`。
     */
    fun buildPlaintext(pairs: List<Pair<String, String>>): String {
        val body = Form.encode(pairs.filter { it.first != "e_r" })
        return if (body.isEmpty()) {
            "{\"queryString\":\"e_r=true\"}"
        } else {
            "{\"content\":\"$body\",\"queryString\":\"e_r=true\"}"
        }
    }

    /**
     * 中间变换：逐字节 XOR `r[i & 15]` → 左循环移位 `rot = (r[0] & 15) % len` → 输出 `r ‖ 结果`。
     *
     * `len == 0` 时 `rot = 0`（参考实现同样处理），否则 `% 0` 会炸。
     */
    fun midTransform(ciphertext: ByteArray, random16: ByteArray): ByteArray {
        require(random16.size == 16) { "mid random must be 16 bytes, got ${random16.size}" }
        val xored = ByteArray(ciphertext.size)
        for (i in ciphertext.indices) {
            xored[i] = (ciphertext[i].toInt() xor random16[i and 0x0f].toInt()).toByte()
        }
        val rot = if (xored.isEmpty()) 0 else (random16[0].toInt() and 0x0f) % xored.size
        val out = ByteArray(16 + xored.size)
        System.arraycopy(random16, 0, out, 0, 16)
        System.arraycopy(xored, rot, out, 16, xored.size - rot)
        System.arraycopy(xored, 0, out, 16 + xored.size - rot, rot)
        return out
    }

    /** HKDF-SHA256 的手写形态，只取前 16 字节作 AES-128-GCM 的 key。 */
    fun deriveAesKey(sharedSecret: ByteArray, ephemeralPublic: ByteArray): ByteArray {
        // 参考实现在 sharedSecret 为空时喂 32 个零字节；照抄，免得两边在边界上分叉。
        val ikm = if (sharedSecret.isEmpty()) ByteArray(32) else sharedSecret
        val prk = hmacSha256(ByteArray(32), ikm)
        return hmacSha256(prk, ephemeralPublic + byteArrayOf(1)).copyOf(16)
    }

    /**
     * `S` = 临时公钥(32) ‖ IV(12) ‖ GCM 密文 ‖ tag(16)。
     *
     * [Aes.gcmEncrypt] 返回的就是「密文 ‖ tag」（JCE `doFinal` 语义），所以这里直接拼，
     * 不要自己去拆 tag —— 拆错一位整条 S 就废了，而夹具能立刻指出来。
     */
    fun encryptS(
        dynamicKey: ByteArray,
        keyState: KeyState,
        os: String,
        ephemeralPrivate: ByteArray,
        iv: ByteArray
    ): ByteArray {
        val ephemeralPublic = X25519.publicKeyFromPrivate(ephemeralPrivate)
        val shared = X25519.x25519(ephemeralPrivate, Base64.decode(keyState.publicKey))
        require(shared.any { it != 0.toByte() }) { "X25519 peer produced an all-zero shared secret" }
        val aesKey = deriveAesKey(shared, ephemeralPublic)
        val plaintext = "${Base64.encode(dynamicKey)}|$os|${keyState.sk}".toByteArray(Charsets.UTF_8)
        val sealed = Aes.gcmEncrypt(aesKey, iv, plaintext)
        return ephemeralPublic + iv + sealed
    }

    /**
     * 组装一次请求的三个参数与表单体。
     *
     * @param session 上一次响应头带回来的 `x-encr-ssid` / `x-encr-sskey`；非空时动态密钥改用
     *   `sskey` 的 ASCII 字节（32 字节 → AES-256），`R` 里带上 ssid。
     */
    fun encryptRequest(
        pairs: List<Pair<String, String>>,
        keyState: KeyState,
        os: String,
        session: Session?,
        rng: XeapiRng
    ): XeapiRequest {
        val sessionKey = session?.key?.toByteArray(Charsets.US_ASCII)
        val dynamicKey = sessionKey ?: rng.dynamicKey

        val plaintext = buildPlaintext(pairs)
        val inner = Aes.ecbEncrypt(STATIC_KEY, plaintext.toByteArray(Charsets.UTF_8))
        val mid = midTransform(inner, rng.midRandom)
        val c = Base64.encode(Aes.ecbEncrypt(dynamicKey, mid), urlSafe = true, pad = false)

        val s = Base64.encode(
            encryptS(dynamicKey, keyState, os, rng.ephemeralPrivate, rng.iv)
        )
        val r = Base64.encode(
            Aes.ecbEncrypt(
                STATIC_KEY,
                "${keyState.version}|${session?.id ?: ""}".toByteArray(Charsets.UTF_8)
            )
        )
        return XeapiRequest(
            plaintext = plaintext,
            c = c,
            s = s,
            r = r,
            body = Form.encode(listOf("C" to c, "S" to s, "R" to r))
        )
    }

    /**
     * 响应解密：AES-128-ECB（eapi key），解出来以 `1f 8b` 开头时再 gunzip。
     *
     * 入参是**原始响应字节**而不是文本：AES 密文不是合法 UTF-8，先按文本读会丢字节。
     */
    fun decryptResponse(body: ByteArray): String {
        val decrypted = Aes.ecbDecrypt(EAPI_KEY, body)
        val plain = if (decrypted.size >= 2 && decrypted[0] == 0x1f.toByte() && decrypted[1] == 0x8b.toByte()) {
            gunzip(decrypted)
        } else {
            decrypted
        }
        return String(plain, Charsets.UTF_8)
    }

    private fun gunzip(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size * 4)
        val buffer = ByteArray(4096)
        GZIPInputStream(ByteArrayInputStream(data)).use { gz ->
            while (true) {
                val n = gz.read(buffer)
                if (n < 0) break
                out.write(buffer, 0, n)
            }
        }
        return out.toByteArray()
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // SecretKeySpec 拒绝空 key，而 HKDF 的 salt 恰好是 32 个零字节（不是空数组），
        // 这里只是把"真的给了空 key"这种调用错误早点暴露出来。
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    /** 公钥状态：`/api/bsr/sk/get` 解出来的 `publicKey` / `version` / `sk`。 */
    data class KeyState(val publicKey: String, val version: String, val sk: String)

    /** 会话：响应头 `x-encr-ssid` / `x-encr-sskey`。 */
    data class Session(val id: String, val key: String)

    /** 一次请求的全部随机量。生产用 [random]，夹具注入固定值。 */
    class XeapiRng(
        val dynamicKey: ByteArray,
        val midRandom: ByteArray,
        val ephemeralPrivate: ByteArray,
        val iv: ByteArray
    ) {
        companion object {
            fun random(): XeapiRng {
                val r = java.security.SecureRandom()
                return XeapiRng(
                    dynamicKey = ByteArray(16).also { r.nextBytes(it) },
                    midRandom = ByteArray(16).also { r.nextBytes(it) },
                    ephemeralPrivate = ByteArray(32).also { r.nextBytes(it) },
                    iv = ByteArray(12).also { r.nextBytes(it) }
                )
            }
        }
    }

    /** 请求体三件套。字段名小写对应夹具里的 `C` / `S` / `R`。 */
    data class XeapiRequest(
        val plaintext: String,
        val c: String,
        val s: String,
        val r: String,
        val body: String
    )
}
