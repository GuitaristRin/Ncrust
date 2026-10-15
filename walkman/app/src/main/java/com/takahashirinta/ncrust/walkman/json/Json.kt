package com.takahashirinta.ncrust.walkman.json

/**
 * 极简 JSON 解析。
 *
 * 为什么不用现成的：
 *  - `org.json`（`JSONObject`）在设备上是真的，但在 **JVM 单测里是 android.jar 的空桩**，
 *    于是接口层一旦用它就永远无法在单测里被验证 —— 而 W0 的验收恰恰只有 JVM 单测。
 *  - Gson 按 `TASKBOOK.md` §1 的白名单**仅限测试**使用，主代码不能引。
 *
 * 所以自带一个只做"读"的递归下降解析器：够用、两端同一条码路、可测。
 * 不支持写入，也不打算支持 —— 请求体的 JSON 都是手拼的（字段顺序是协议的一部分，
 * 交给序列化器反而危险）。
 *
 * 数字保留**原始文本**（[Json.Num.raw]）而不是直接转 `Double`：歌曲 id 是 19 位的长整型，
 * 先过 `Double` 会在第 16 位就开始丢精度，`id` 一变，取链、收藏、歌词全都对不上。
 */
sealed class Json {

    object Null : Json()

    data class Bool(val value: Boolean) : Json()

    /** 数字的原始文本，取值时再定类型（见类注释：`Double` 会丢 id 精度）。 */
    data class Num(val raw: String) : Json()

    data class Str(val value: String) : Json()

    data class Arr(val items: List<Json>) : Json()

    data class Obj(val fields: Map<String, Json>) : Json()

    val asObjOrNull: Obj? get() = this as? Obj
    val asArrOrNull: Arr? get() = this as? Arr

    fun strOrNull(key: String): String? = (field(key) as? Str)?.value

    fun longOrNull(key: String): Long? = (field(key) as? Num)?.raw?.toLongOrNull()

    fun intOrNull(key: String): Int? = longOrNull(key)?.toInt()

    fun boolOrNull(key: String): Boolean? = (field(key) as? Bool)?.value

    fun objOrNull(key: String): Obj? = field(key) as? Obj

    fun arrOrNull(key: String): Arr? = field(key) as? Arr

    fun field(key: String): Json? = (this as? Obj)?.fields?.get(key)

    companion object {

        /** 解析失败抛 [IllegalArgumentException]，带出错位置的上下文。 */
        fun parse(text: String): Json = Parser(text).parseDocument()

        /** 空串 / 全空白当作 `null` 而不是错误：网易的接口偶尔回空体。 */
        fun parseOrNull(text: String?): Json? =
            if (text.isNullOrBlank()) null else try {
                parse(text)
            } catch (_: IllegalArgumentException) {
                null
            }

        /**
         * 把字符串转成 JSON 字符串字面量（带引号、转义）。
         *
         * 请求体一律手拼（字段顺序是协议的一部分），所以需要一个能安全嵌入值的助手：
         * `song/enhance/player/url/v1` 的 `header` 字段本身就是一段 JSON 文本，
         * 拼错一个引号服务端只会回一个看不懂的错误码。
         */
        fun quote(value: String): String {
            val sb = StringBuilder(value.length + 2)
            sb.append('"')
            for (c in value) {
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append('"')
            return sb.toString()
        }
    }

    private class Parser(private val s: String) {

        private var i = 0

        fun parseDocument(): Json {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (i != s.length) fail("解析结束后还有多余字符")
            return value
        }

        private fun parseValue(): Json {
            if (i >= s.length) fail("内容意外结束")
            return when (s[i]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> Str(parseString())
                't' -> literal("true", Bool(true))
                'f' -> literal("false", Bool(false))
                'n' -> literal("null", Null)
                else -> parseNumber()
            }
        }

        private fun parseObject(): Json {
            expect('{')
            val fields = LinkedHashMap<String, Json>()
            skipWhitespace()
            if (peek() == '}') {
                i++
                return Obj(fields)
            }
            while (true) {
                skipWhitespace()
                val key = parseString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                fields[key] = parseValue()
                skipWhitespace()
                when (val c = peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return Obj(fields)
                    }

                    else -> fail("对象里期望 ',' 或 '}'，实际是 '$c'")
                }
            }
        }

        private fun parseArray(): Json {
            expect('[')
            val items = ArrayList<Json>()
            skipWhitespace()
            if (peek() == ']') {
                i++
                return Arr(items)
            }
            while (true) {
                skipWhitespace()
                items += parseValue()
                skipWhitespace()
                when (val c = peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return Arr(items)
                    }

                    else -> fail("数组里期望 ',' 或 ']'，实际是 '$c'")
                }
            }
        }

        private fun parseString(): String {
            expect('"')
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("字符串没有收尾的引号")
                when (val c = s[i++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) fail("转义符后内容结束")
                        when (val esc = s[i++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) fail("\\u 转义不完整")
                                val hex = s.substring(i, i + 4)
                                val code = hex.toIntOrNull(16) ?: fail("\\u 转义不是十六进制: $hex")
                                i += 4
                                sb.append(code.toChar())
                            }

                            else -> fail("不认识的转义 \\$esc")
                        }
                    }

                    else -> {
                        if (c < ' ') fail("字符串包含未转义控制字符")
                        sb.append(c)
                    }
                }
            }
        }

        private fun parseNumber(): Json {
            val start = i
            if (peek() == '-') i++
            if (i >= s.length) fail("数字缺整数部分")
            when (s[i]) {
                '0' -> i++
                in '1'..'9' -> digits()
                else -> fail("数字缺整数部分")
            }
            if (i < s.length && s[i] == '.') {
                i++
                digits()
            }
            if (i < s.length && (s[i] == 'e' || s[i] == 'E')) {
                i++
                if (i < s.length && (s[i] == '+' || s[i] == '-')) i++
                digits()
            }
            return Num(s.substring(start, i))
        }

        private fun digits() {
            val start = i
            while (i < s.length && s[i] in '0'..'9') i++
            if (i == start) fail("数字缺少数位")
        }

        private fun literal(text: String, value: Json): Json {
            if (!s.startsWith(text, i)) fail("期望字面量 $text")
            i += text.length
            return value
        }

        private fun expect(c: Char) {
            if (peek() != c) fail("期望 '$c'，实际是 '${if (i < s.length) s[i] else "<结束>"}'")
            i++
        }

        private fun peek(): Char = if (i < s.length) s[i] else fail("内容意外结束")

        private fun skipWhitespace() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
        }

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("JSON 解析失败（位置 $i）：$message")
    }
}
