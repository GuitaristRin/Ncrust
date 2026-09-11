package com.takahashirinta.ncrust.auth

import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 方案 A「一码二用」的局域网配对协议与加密。
 *
 * 平板显示官方登录二维码（内容仍是标准 `music.163.com/login?codekey=<unikey>`）：
 *  - 官方网易云 App 扫 → 官方确认 unikey → 平板轮询拿到 cookie，走官方登录；
 *  - Ncrust 手机扫 → 解析出 unikey，经局域网把本机会话 cookie 回传给平板。
 *
 * 二维码本身不含平板地址：手机用 UDP 广播按 unikey 询问，平板应答自己的 IP+TCP 端口。
 * 安全上以 unikey（屏幕可见的一次性短时密钥）派生 AES-128 加密 cookie，平板仅在
 * 正在展示该 unikey 的二维码期间监听，成功后立即停止。
 */
object QrPair {
    const val UDP_PORT = 47_821

    private const val REQ_PREFIX = "NCRUSTPAIR1?"
    private const val RESP_PREFIX = "NCRUSTPAIR1!"

    fun requestPayload(unikey: String) = "$REQ_PREFIX$unikey"

    fun responsePayload(unikey: String, tcpPort: Int) = "$RESP_PREFIX$unikey:$tcpPort"

    /** 从 UDP 请求解析 unikey；非本协议返回 null。 */
    fun parseRequest(payload: String): String? =
        payload.takeIf { it.startsWith(REQ_PREFIX) }
            ?.removePrefix(REQ_PREFIX)
            ?.takeIf { it.isNotEmpty() }

    /** 解析应答为 (unikey, tcpPort)；格式不符返回 null。 */
    fun parseResponse(payload: String): Pair<String, Int>? {
        if (!payload.startsWith(RESP_PREFIX)) return null
        val rest = payload.removePrefix(RESP_PREFIX)
        val idx = rest.lastIndexOf(':')
        if (idx <= 0) return null
        val port = rest.substring(idx + 1).toIntOrNull() ?: return null
        return rest.substring(0, idx) to port
    }

    private fun key(unikey: String): SecretKeySpec {
        val digest = MessageDigest.getInstance("SHA-256").digest(unikey.toByteArray(Charsets.UTF_8))
        return SecretKeySpec(digest.copyOf(16), "AES")
    }

    /** AES-128-CBC，随机 IV 前置。 */
    fun encrypt(unikey: String, plain: String): ByteArray {
        val iv = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key(unikey), IvParameterSpec(iv))
        return iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
    }

    fun decrypt(unikey: String, data: ByteArray): String? = runCatching {
        if (data.size <= 16) return null
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, key(unikey), IvParameterSpec(data.copyOfRange(0, 16)))
        String(cipher.doFinal(data.copyOfRange(16, data.size)), Charsets.UTF_8)
    }.getOrNull()

    /** 从二维码内容解析 unikey：支持标准登录链接，也兼容裸 unikey。 */
    fun unikeyFromQrContent(content: String): String? {
        val trimmed = content.trim()
        if (!trimmed.startsWith("http")) return trimmed.takeIf { it.isNotEmpty() }
        return runCatching {
            URL(trimmed).query
                ?.split('&')
                ?.mapNotNull { it.split('=', limit = 2).takeIf { p -> p.size == 2 } }
                ?.firstOrNull { it[0] == "codekey" }
                ?.get(1)
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }
}
