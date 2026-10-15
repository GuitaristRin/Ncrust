package com.takahashirinta.ncrust.walkman

import com.takahashirinta.ncrust.walkman.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自带 JSON 解析器的测试。
 *
 * 这个解析器是接口层的唯一入口（主代码不能引 Gson，`org.json` 在 JVM 单测里是空桩），
 * 所以它错了就是所有接口都错了 —— 值得几条用例。
 */
class JsonTest {

    @Test
    fun parsesNestedStructures() {
        val root = Json.parse(
            """{"code":200,"data":{"songs":[{"id":1,"name":"a"},{"id":2,"name":"b"}],"ok":true,"none":null}}"""
        )
        assertEquals(200, root.intOrNull("code"))
        val songs = root.objOrNull("data")!!.arrOrNull("songs")!!.items
        assertEquals(2, songs.size)
        assertEquals("b", songs[1].strOrNull("name"))
        assertEquals(true, root.objOrNull("data")!!.boolOrNull("ok"))
        assertNull(root.objOrNull("data")!!.strOrNull("none"))
    }

    /**
     * 19 位歌曲 id 必须原样保留。
     *
     * 这是数字保留原始文本（而不是先转 `Double`）的原因：`Double` 只有 53 位有效位，
     * 19 位十进制整数从第 16 位就开始丢精度，`id` 一变，取链 / 收藏 / 歌词全都对不上。
     */
    @Test
    fun keepsLongIdsExact() {
        val id = 2_123_456_789_012_345_678L
        val root = Json.parse("""{"id":$id}""")
        assertEquals(id, root.longOrNull("id"))
    }

    @Test
    fun handlesEscapes() {
        val root = Json.parse("""{"s":"a\"b\\c\nd\te\u0041\u4e2d"}""")
        assertEquals("a\"b\\c\nd\teA中", root.strOrNull("s"))
    }

    @Test
    fun emptyBodyIsNullNotError() {
        // 网易的接口偶尔回空体；这里判成"没有内容"而不是抛异常，调用方再决定怎么报。
        assertNull(Json.parseOrNull(""))
        assertNull(Json.parseOrNull("   \n "))
    }

    @Test
    fun malformedJsonThrows() {
        assertThrows(IllegalArgumentException::class.java) { Json.parse("""{"a":}""") }
        assertThrows(IllegalArgumentException::class.java) { Json.parse("""{"a":1""") }
        assertThrows(IllegalArgumentException::class.java) { Json.parse("""{"a":1}extra""") }
    }

    @Test
    fun rejectsMalformedNumbersAndUnescapedControls() {
        for (text in listOf("-", "01", "1.", "1e", "1e+", "1+2", "--1", "\"raw\nnewline\"")) {
            assertThrows("accepted $text", IllegalArgumentException::class.java) { Json.parse(text) }
            assertNull("nullable parser accepted $text", Json.parseOrNull(text))
        }
        assertNull(Json.parseOrNull("<html>gateway failure</html>"))
    }

    @Test
    fun preservesValidNumericForms() {
        for (text in listOf("0", "-0", "-123", "1.25", "1e+2", "-0.5E-3")) {
            assertEquals(text, (Json.parse(text) as Json.Num).raw)
        }
    }

    /** 请求体是手拼的，[Json.quote] 是唯一的转义出口；拼错一个引号服务端只会回一个看不懂的码。 */
    @Test
    fun quoteRoundTrips() {
        val raw = "{\"os\":\"pc\",\"deviceId\":\"pyncm!\"}\n换行\"引号\"\\反斜杠"
        val parsed = Json.parse("{\"header\":${Json.quote(raw)}}")
        assertEquals(raw, parsed.strOrNull("header"))
    }

    @Test
    fun quoteEscapesControlCharacters() {
        val quoted = Json.quote("\u0001")
        assertTrue("控制字符要编成 \\u 形式，实际是 $quoted", quoted.contains("\\u0001"))
    }
}
