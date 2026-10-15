package com.takahashirinta.ncrust.walkman.crypto

import java.security.spec.AlgorithmParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES 三种模式的薄封装。
 *
 * 这个类存在的唯一理由是 **GCM 的参数类型两端不一样**：
 *
 *  - JVM（单测）：SunJCE 的 GCM **只认** `GCMParameterSpec`。传 `IvParameterSpec` 直接抛
 *    `InvalidAlgorithmParameterException: AlgorithmParameterSpec not of GCMParameterSpec`（本机实测）。
 *  - 设备（API 16）：`GCMParameterSpec` 是 API 19 才有的类，**根本不存在**，只能用 `IvParameterSpec`。
 *    Conscrypt 认这条路：`OpenSSLAeadCipher.engineInitInternal` 里有 `instanceof IvParameterSpec`
 *    分支，而它先调的 `Platform.fromGCMParameterSpec` 内部就是 `Class.forName` + try/catch，
 *    取不到类就返回 null，于是正好落到 IvParameterSpec 分支（反编译 conscrypt-android:2.5.3 得到）。
 *
 * 所以 spec 必须按运行时能力选，而且**只能用反射构造 `GCMParameterSpec`**：直接 `new` 会让 Dalvik
 * 在类加载期就去解析一个 API 16 上不存在的类，那是 `NoClassDefFoundError`，不是能 catch 的异常。
 */
object Aes {

    private const val GCM_TAG_BITS = 128

    fun ecbEncrypt(key: ByteArray, data: ByteArray): ByteArray = ecb(key, data, encrypt = true)

    fun ecbDecrypt(key: ByteArray, data: ByteArray): ByteArray = ecb(key, data, encrypt = false)

    fun cbcEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray {
        require(iv.size == 16) { "AES-CBC iv must be 16 bytes, got ${iv.size}" }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    /** 返回「密文 ‖ tag(16)」，与 JDK / Conscrypt 的 `doFinal` 语义一致（xeapi 的 S 直接拼它）。 */
    fun gcmEncrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        gcm(key, iv, data, encrypt = true)

    fun gcmDecrypt(key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray =
        gcm(key, iv, data, encrypt = false)

    private fun ecb(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES")
        )
        return cipher.doFinal(data)
    }

    private fun gcm(key: ByteArray, iv: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            gcmSpec(iv)
        )
        return cipher.doFinal(data)
    }

    /**
     * 优先 `GCMParameterSpec(128, iv)`；该类不存在（API 16~18）时退回 `IvParameterSpec(iv)`，
     * Conscrypt 在那条路上的默认 tag 长度就是 128 位。
     */
    private fun gcmSpec(iv: ByteArray): AlgorithmParameterSpec {
        val ctor = GCM_SPEC_CONSTRUCTOR
        if (ctor != null) {
            try {
                return ctor.newInstance(GCM_TAG_BITS, iv) as AlgorithmParameterSpec
            } catch (_: Throwable) {
                // 反射构造失败就按老平台处理，别让一条日志路径把加密整个带崩
            }
        }
        return IvParameterSpec(iv)
    }

    private val GCM_SPEC_CONSTRUCTOR: java.lang.reflect.Constructor<*>? by lazy {
        runCatching {
            Class.forName("javax.crypto.spec.GCMParameterSpec")
                .getConstructor(Int::class.javaPrimitiveType, ByteArray::class.java)
        }.getOrNull()
    }
}
