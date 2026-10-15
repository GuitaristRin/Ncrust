package com.takahashirinta.ncrust.walkman.crypto

/**
 * 十六进制编解码。
 *
 * 自己写而不是用平台库：`java.util.HexFormat` 要 API 34（minSdk 是 16），`android.util.Hex`
 * 在 JVM 单测里是空桩 —— 而 eapi 的产物就是 hex，单测必须能逐字节对照夹具。
 */
object Hex {

    private const val DIGITS = "0123456789abcdef"

    fun encode(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xff
            sb.append(DIGITS[v ushr 4]).append(DIGITS[v and 0x0f])
        }
        return sb.toString()
    }

    /** 长度奇数或含非 hex 字符即抛异常 —— 静默容错会让错的向量看起来"通过"。 */
    fun decode(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd-length hex string (${hex.length})" }
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            require(hi >= 0 && lo >= 0) {
                "invalid hex at index ${i * 2}: '${hex[i * 2]}${hex[i * 2 + 1]}'"
            }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}
