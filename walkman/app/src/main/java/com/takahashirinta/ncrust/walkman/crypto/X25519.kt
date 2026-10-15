package com.takahashirinta.ncrust.walkman.crypto

import java.math.BigInteger

/**
 * X25519（RFC 7748）纯 Kotlin 实现。
 *
 * 为什么要自己写：设备是 Android 4.1（API 16），`KeyPairGenerator.getInstance("X25519")` 是
 * **API 33** 才进 JCA 的，4.1 上连 Conscrypt 都不提供这条算法（Conscrypt 的 X25519 只作为
 * TLS 套件内部使用，没有暴露成 JCA KeyAgreement）。xeapi 的 `S` 参数又必须现算一次 ECDH，
 * 所以这一段只能自带。
 *
 * 用 `BigInteger` 表达 Montgomery ladder；此实现不是常数时间的。
 * 私钥每请求重新生成，设备上的执行耗时留待真机验证。
 *
 * 坐标与标量一律 32 字节小端序，与 RFC 一致。
 */
object X25519 {

    private const val KEY_BYTES = 32

    // BigInteger.TWO 是 Java 9 才有的常量，Android 4.1 上不存在（NoSuchFieldError），只能 valueOf(2)。
    private val TWO: BigInteger = BigInteger.valueOf(2)

    /** p = 2^255 - 19。 */
    private val P: BigInteger = TWO.pow(255).subtract(BigInteger.valueOf(19))

    /** a24 = (486662 - 2) / 4，RFC 7748 §5 的 Montgomery 阶梯常数。 */
    private val A24: BigInteger = BigInteger.valueOf(121665)

    /** 基点 u = 9。 */
    private const val BASE_POINT_U = 9

    /** 由 32 字节私钥求公钥。 */
    fun publicKeyFromPrivate(privateKey: ByteArray): ByteArray {
        val base = ByteArray(KEY_BYTES)
        base[0] = BASE_POINT_U.toByte()
        return x25519(privateKey, base)
    }

    /**
     * `X25519(privateKey, peerPublicKey)` → 32 字节共享密钥（小端）。
     *
     * clamp 在函数内完成（RFC 7748 的 `decodeScalar25519`），调用方不必先处理私钥；
     * 对端公钥的最高位按规范丢弃（`decodeUCoordinate`），那一位在 RFC 7748 里是保留位。
     */
    fun x25519(privateKey: ByteArray, peerPublicKey: ByteArray): ByteArray {
        require(privateKey.size == KEY_BYTES) { "X25519 private key must be 32 bytes, got ${privateKey.size}" }
        require(peerPublicKey.size == KEY_BYTES) { "X25519 public key must be 32 bytes, got ${peerPublicKey.size}" }

        val k = privateKey.copyOf()
        k[0] = (k[0].toInt() and 248).toByte()
        k[31] = ((k[31].toInt() and 127) or 64).toByte()

        val u = peerPublicKey.copyOf()
        u[31] = (u[31].toInt() and 127).toByte()

        val x1 = leToBig(u)
        var x2 = BigInteger.ONE
        var z2 = BigInteger.ZERO
        var x3 = x1
        var z3 = BigInteger.ONE
        var swap = 0

        for (t in 254 downTo 0) {
            val bit = (k[t shr 3].toInt() shr (t and 7)) and 1
            swap = swap xor bit
            if (swap == 1) {
                val tx = x2; x2 = x3; x3 = tx
                val tz = z2; z2 = z3; z3 = tz
            }
            swap = bit

            val a = mod(x2 + z2)
            val aa = mod(a * a)
            val b = mod(x2 - z2)
            val bb = mod(b * b)
            val e = mod(aa - bb)
            val c = mod(x3 + z3)
            val d = mod(x3 - z3)
            val da = mod(d * a)
            val cb = mod(c * b)
            x3 = mod((da + cb).pow(2))
            z3 = mod(x1 * mod((da - cb).pow(2)))
            x2 = mod(aa * bb)
            z2 = mod(e * (aa + A24 * e))
        }

        // RFC 7748 在循环后还做一次 cswap(x2,x3)/(z2,z3)。x3/z3 此后不再被读，所以这里
        // 用选择表达式代替赋值交换：结果相同，也免得编译器把"赋了不读"当成可疑代码报出来。
        val rx = if (swap == 1) x3 else x2
        val rz = if (swap == 1) z3 else z2

        // 低阶点输出全零，由 xeapi 加密层拒绝继续使用。
        if (rz.signum() == 0) return ByteArray(KEY_BYTES)
        return bigToLe32(mod(rx * rz.modInverse(P)))
    }

    private fun mod(v: BigInteger): BigInteger {
        val r = v.mod(P)
        return r
    }

    private fun leToBig(bytes: ByteArray): BigInteger = BigInteger(1, bytes.reversedArray())

    /** 固定输出 32 字节小端；`v` 超过 2^256 的部分按规范截断（本算法不会发生）。 */
    private fun bigToLe32(v: BigInteger): ByteArray {
        val out = ByteArray(KEY_BYTES)
        val mask = BigInteger.valueOf(0xff)
        var x = v
        for (i in 0 until KEY_BYTES) {
            out[i] = x.and(mask).toInt().toByte()
            x = x.shiftRight(8)
        }
        return out
    }
}
