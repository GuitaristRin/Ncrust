package com.takahashirinta.ncrust.walkman.crypto

import java.io.ByteArrayOutputStream

/**
 * base64 编解码，自己实现。
 *
 * 为什么不用平台库：`java.util.Base64` 要 API 26（minSdk 是 16），`android.util.Base64` 在 JVM
 * 单测里是空桩 —— 而 xeapi 的易错点**全在编码细节上**（`C` 用 base64url 且不补 `=`，`S`/`R` 用
 * 标准 base64 且补 `=`），恰恰必须能在单测里逐字节对照夹具。自己写六十行，设备与 JVM 走同一条码路。
 */
object Base64 {

    private const val STD_TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private const val URL_TABLE = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /**
     * @param urlSafe 用 `-` `_` 代替 `+` `/`（xeapi 的 `C` 要这个）。
     * @param pad 是否补 `=`（xeapi 的 `C` 不补，`S`/`R` 补）。
     */
    fun encode(data: ByteArray, urlSafe: Boolean = false, pad: Boolean = true): String {
        val table = if (urlSafe) URL_TABLE else STD_TABLE
        val out = StringBuilder((data.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= data.size) {
            val n = ((data[i].toInt() and 0xff) shl 16) or
                ((data[i + 1].toInt() and 0xff) shl 8) or
                (data[i + 2].toInt() and 0xff)
            out.append(table[n ushr 18 and 0x3f])
                .append(table[n ushr 12 and 0x3f])
                .append(table[n ushr 6 and 0x3f])
                .append(table[n and 0x3f])
            i += 3
        }
        when (data.size - i) {
            1 -> {
                val n = (data[i].toInt() and 0xff) shl 16
                out.append(table[n ushr 18 and 0x3f]).append(table[n ushr 12 and 0x3f])
                if (pad) out.append("==")
            }
            2 -> {
                val n = ((data[i].toInt() and 0xff) shl 16) or ((data[i + 1].toInt() and 0xff) shl 8)
                out.append(table[n ushr 18 and 0x3f])
                    .append(table[n ushr 12 and 0x3f])
                    .append(table[n ushr 6 and 0x3f])
                if (pad) out.append('=')
            }
        }
        return out.toString()
    }

    /**
     * 解码。两种字母表都认、`=` 与空白忽略 —— 服务端两种写法混用（`C` 不补 `=`、`S` 补），
     * 让调用方去区分字母表只会多一处出错的地方。非法字符抛异常而不是跳过。
     */
    fun decode(text: String): ByteArray {
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
}
