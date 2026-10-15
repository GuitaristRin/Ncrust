package com.takahashirinta.ncrust.walkman.net

import java.io.UnsupportedEncodingException
import java.net.URLEncoder

/**
 * `application/x-www-form-urlencoded` 序列化。
 *
 * 单独成文件是因为**这里的语义是协议的一部分，不是顺手调个库**：xeapi 的明文信封、eapi/weapi 的
 * 表单体都要求 WHATWG 语义（空格 → `+`；`A–Z a–z 0–9 * - . _` 原样；其余含 `! ' ( ) ~` 按 UTF-8
 * 编成**大写** `%XX`）。夹具 `xeapi.plaintext.tricky` 就是专门盯这条的：
 * `q=a+b%21%27%28%29%7E*-._`。
 *
 * `java.net.URLEncoder` 的编码集恰好与 WHATWG 一致（`~` → `%7E`、`*` 保留、空格 → `+`），
 * 所以直接用它；但**它的方法名有误导性**——它做的是表单编码，不是 URL 编码，别拿它去拼路径。
 */
object Form {

    /** 按 `k=v` 顺序拼成表单体；空列表得到空串。 */
    fun encode(pairs: List<Pair<String, String>>): String {
        val sb = StringBuilder()
        for ((k, v) in pairs) {
            if (sb.isNotEmpty()) sb.append('&')
            sb.append(encodeComponent(k)).append('=').append(encodeComponent(v))
        }
        return sb.toString()
    }

    fun encodeComponent(value: String): String = try {
        URLEncoder.encode(value, "UTF-8")
    } catch (_: UnsupportedEncodingException) {
        // UTF-8 是 JVM 规范强制支持的字符集，走不到这里；留着是为了不让一个受检异常
        // 传染到每个调用点（API 16 上也没有 StandardCharsets 的 encode 重载）。
        throw IllegalStateException("UTF-8 not supported")
    }
}
