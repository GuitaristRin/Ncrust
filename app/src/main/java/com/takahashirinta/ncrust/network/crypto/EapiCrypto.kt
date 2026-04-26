package com.takahashirinta.ncrust.network.crypto

import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import java.security.MessageDigest

object EapiCrypto {
    private val AES_KEY = "e82ckenh8dichen8".toByteArray()
    private const val MAGIC_PREFIX = "nobody"
    private const val MAGIC_SUFFIX = "md5forencrypt"
    private const val SEPARATOR = "-36cd479b6b5-"

    fun encryptParams(url: String, payload: Map<String, Any>): String {
        val parsedUrl = java.net.URL(url)
        val urlPath = parsedUrl.path.replace("/eapi/", "/api/")
        val payloadJson = JSONObject(payload).toString()

        val signStr = "$MAGIC_PREFIX${urlPath}use${payloadJson}$MAGIC_SUFFIX"
        val digest = md5(signStr)
        val paramsStr = "${urlPath}${SEPARATOR}${payloadJson}${SEPARATOR}${digest}"

        return aesEncrypt(paramsStr)
    }

    private fun aesEncrypt(data: String): String {
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        val keySpec = SecretKeySpec(AES_KEY, "AES")
        cipher.init(Cipher.ENCRYPT_MODE, keySpec)
        val encrypted = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
        return bytesToHex(encrypted)
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytesToHex(hash)
    }

    private fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }
}