package com.takahashirinta.ncrust.walkman.crypto

import java.security.MessageDigest

/**
 * eapi 加密（`spec/fixtures/crypto/README.md` §eapi.json，答案见 `spec/fixtures/crypto/eapi.json`）。
 *
 * 形态：`POST params=<hex>`。签名只覆盖**重写后**的路径，加密体把路径、JSON、摘要用固定分隔符串起来。
 * 三处易错：`/eapi/` → `/api/` 必须先做（签名与加密体都用重写后的路径）、摘要串的前后缀
 * （`nobody` / `md5forencrypt`）、AES 是 ECB 且输出小写 hex。
 */
object Eapi {

    /** 与官方客户端同一把固定钥匙（AES-128）。 */
    private const val AES_KEY = "e82ckenh8dichen8"

    private const val MAGIC_PREFIX = "nobody"
    private const val MAGIC_SUFFIX = "md5forencrypt"
    private const val SEPARATOR = "-36cd479b6b5-"

    /**
     * 从「完整 URL 或裸路径」里取出参与签名/加密的路径，并把 `/eapi/` 重写成 `/api/`。
     *
     * 不用 `java.net.URL`：夹具里两种形态都有（`/eapi/v2/...` 与 `https://host/eapi/...`），
     * 而裸路径喂给 `URL` 会抛 `MalformedURLException` —— 单测第一条用例就会挂。
     */
    fun apiPath(urlOrPath: String): String {
        var s = urlOrPath
        val schemeEnd = s.indexOf("://")
        if (schemeEnd >= 0) {
            val pathStart = s.indexOf('/', schemeEnd + 3)
            s = if (pathStart < 0) "/" else s.substring(pathStart)
        }
        // query / fragment 不参与签名（签名串里只有 path）
        val cut = s.indexOfFirst { it == '?' || it == '#' }
        if (cut >= 0) s = s.substring(0, cut)
        return s.replace("/eapi/", "/api/")
    }

    /**
     * 生成表单字段 `params`（小写 hex）。
     *
     * @param payloadJson 业务 JSON 的**文本**。整段文本参与签名与加密，字段顺序/转义一变结果就变，
     *   所以调用方要保证序列化方式稳定（本工程手拼 JSON 文本，不走反射序列化器）。
     */
    fun encryptParams(urlOrPath: String, payloadJson: String): String {
        val path = apiPath(urlOrPath)
        val digest = md5Hex("$MAGIC_PREFIX$path" + "use" + payloadJson + MAGIC_SUFFIX)
        val envelope = path + SEPARATOR + payloadJson + SEPARATOR + digest
        return Hex.encode(Aes.ecbEncrypt(AES_KEY.toByteArray(Charsets.UTF_8), envelope.toByteArray(Charsets.UTF_8)))
    }

    /**
     * 解密 eapi 响应体（部分写接口回 AES-ECB 密文的 base64）。
     *
     * 尾巴上的 `\r` 是服务端填充遗留，裁掉；解不开返回 null 而不是空串，让调用方能区分
     * 「响应本来就不是加密的」与「解出来是空」。
     */
    fun decryptResponse(base64Body: String): String? {
        val bytes = try {
            Base64.decode(base64Body)
        } catch (_: IllegalArgumentException) {
            return null
        }
        if (bytes.isEmpty()) return null
        return try {
            val plain = Aes.ecbDecrypt(AES_KEY.toByteArray(Charsets.UTF_8), bytes)
            String(plain, Charsets.UTF_8).trimEnd('\r', '\n', ' ')
        } catch (_: Exception) {
            null
        }
    }

    private fun md5Hex(input: String): String =
        Hex.encode(MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8)))
}
