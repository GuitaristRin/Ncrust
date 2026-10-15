package com.takahashirinta.ncrust.auth

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 扫码登录协议层的单测：PC 身份 header、加密信封、Set-Cookie → cookie 串。
 *
 * 这些是「与参考实现逐字段对照」的部分（协议见 `spec/api/endpoints.md` 的
 * 「eapi · PC 客户端指纹」），抽成纯函数就是为了能在本地直接比对，不必上设备。
 *
 * 边界：真正做 JSON 序列化的是 `EapiCrypto` 里的 `org.json.JSONObject`，而本地单测里
 * `org.json` 是空桩（一调就抛 "not mocked"，见 `XeapiCryptoTest`）。这里用 Gson 渲染同一份
 * `Map` 来断言字段顺序与原生类型（数值 / 布尔）；两者对 `Map` 的语义一致（保留插入顺序、
 * `Map` 值递归成 JSON 对象、`Int`/`Boolean` 写成裸字面量），但**「JSONObject 也这么写」这一步
 * 是看代码得出的，不是这份单测证明的** —— 端到端的那一步由 Windows Core 测试
 * （`QrLoginClientTests.DecryptParams` 解真请求体）覆盖。
 */
class QrLoginProtocolTest {

    private val deviceId = "0123456789abcdef0123456789abcdef"

    @Test
    fun `pc header has reference field order and values`() {
        val header = QrLoginProtocol.pcHeader(deviceId, nowMs = 1_700_000_000_000L, requestSuffix = 42)

        assertEquals(
            listOf(
                "osver", "deviceId", "os", "appver", "versioncode", "mobilename",
                "buildver", "resolution", "__csrf", "channel", "requestId"
            ),
            header.keys.toList()
        )
        assertEquals("Microsoft-Windows-10-Professional-build-19045-64bit", header["osver"])
        assertEquals(deviceId, header["deviceId"])
        assertEquals("pc", header["os"])
        assertEquals("3.1.17.204416", header["appver"])
        assertEquals("140", header["versioncode"])
        assertEquals("", header["mobilename"])
        // buildver 是毫秒时间戳前 10 位 == 秒级时间戳
        assertEquals("1700000000", header["buildver"])
        assertEquals("1920x1080", header["resolution"])
        assertEquals("", header["__csrf"])
        assertEquals("netease", header["channel"])
        assertEquals("1700000000000_0042", header["requestId"])
    }

    @Test
    fun `request id suffix is padded to four digits`() {
        val header = QrLoginProtocol.pcHeader(deviceId, nowMs = 1L, requestSuffix = 7)

        assertEquals("1_0007", header["requestId"])
    }

    @Test
    fun `cookie header encodes key and value pair wise`() {
        val cookie = QrLoginProtocol.cookieHeader(
            linkedMapOf("os" to "pc", "mobilename" to "", "resolution" to "1920x1080")
        )

        assertEquals("os=pc; mobilename=; resolution=1920x1080", cookie)
    }

    @Test
    fun `cookie header matches reference encodeURIComponent charset`() {
        // encodeURIComponent 保留 `!~*'()`，空格是 %20（不是 URLEncoder 的 `+`），非 ASCII 走 UTF-8 %XX。
        assertEquals("-_.!~*'()", QrLoginProtocol.encodeComponent("-_.!~*'()"))
        assertEquals("a%20b", QrLoginProtocol.encodeComponent("a b"))
        assertEquals("%E4%B8%AD", QrLoginProtocol.encodeComponent("中"))
        assertEquals("%3D%3B%26", QrLoginProtocol.encodeComponent("=;&"))
    }

    @Test
    fun `unikey envelope is business fields then e_r then header`() {
        val header = QrLoginProtocol.pcHeader(deviceId, nowMs = 1_700_000_000_000L, requestSuffix = 1)
        val payload = QrLoginProtocol.envelope(QrLoginProtocol.unikeyFields(), header)

        assertEquals(listOf("type", "e_r", "header"), payload.keys.toList())
        assertEquals(3, payload["type"])
        assertEquals(false, payload["e_r"])
        assertEquals(header, payload["header"])
    }

    @Test
    fun `envelope serialises to the reference json text`() {
        val header = QrLoginProtocol.pcHeader(deviceId, nowMs = 1_700_000_000_000L, requestSuffix = 42)

        assertEquals(
            "{\"type\":3,\"e_r\":false,\"header\":{" +
                "\"osver\":\"Microsoft-Windows-10-Professional-build-19045-64bit\"," +
                "\"deviceId\":\"$deviceId\",\"os\":\"pc\",\"appver\":\"3.1.17.204416\"," +
                "\"versioncode\":\"140\",\"mobilename\":\"\",\"buildver\":\"1700000000\"," +
                "\"resolution\":\"1920x1080\",\"__csrf\":\"\",\"channel\":\"netease\"," +
                "\"requestId\":\"1700000000000_0042\"}}",
            Gson().toJson(QrLoginProtocol.envelope(QrLoginProtocol.unikeyFields(), header))
        )
    }

    @Test
    fun `client login envelope keeps key before type`() {
        val header = QrLoginProtocol.pcHeader(deviceId, nowMs = 1L, requestSuffix = 0)

        assertEquals(
            "{\"key\":\"KEY\\\"X\",\"type\":3,\"e_r\":false,\"header\":{" +
                "\"osver\":\"Microsoft-Windows-10-Professional-build-19045-64bit\"," +
                "\"deviceId\":\"$deviceId\",\"os\":\"pc\",\"appver\":\"3.1.17.204416\"," +
                "\"versioncode\":\"140\",\"mobilename\":\"\",\"buildver\":\"0\"," +
                "\"resolution\":\"1920x1080\",\"__csrf\":\"\",\"channel\":\"netease\"," +
                "\"requestId\":\"1_0000\"}}",
            Gson().toJson(QrLoginProtocol.envelope(QrLoginProtocol.clientLoginFields("KEY\"X"), header))
        )
    }

    @Test
    fun `qr content is pc form without chainId`() {
        val content = QrLoginProtocol.qrContent("UNIKEY123")

        assertEquals("https://music.163.com/login?codekey=UNIKEY123", content)
        assertFalse(content.contains("chainId"))
    }

    @Test
    fun `session cookie keeps last non empty music u`() {
        // 同一个响应里可能先出现删除用的空值（顺序不定）：取最后一个非空值。
        assertEquals(
            "MUSIC_U=real; __csrf=x",
            QrLoginProtocol.sessionCookie(listOf("MUSIC_U=; Path=/", "MUSIC_U=real; Path=/; HttpOnly", "__csrf=x; Path=/"))
        )
        assertEquals(
            "MUSIC_U=real",
            QrLoginProtocol.sessionCookie(listOf("MUSIC_U=real; Path=/; HttpOnly", "MUSIC_U=; Path=/; Max-Age=0"))
        )
    }

    @Test
    fun `session cookie dedupes same name`() {
        val cookie = QrLoginProtocol.sessionCookie(
            listOf("MUSIC_U=a; Path=/", "MUSIC_U=b; Path=/", "NMTID=1; Path=/")
        )

        assertEquals("MUSIC_U=b; NMTID=1", cookie)
    }

    @Test
    fun `session cookie without non empty music u is null`() {
        assertNull(QrLoginProtocol.sessionCookie(emptyList()))
        assertNull(QrLoginProtocol.sessionCookie(listOf("NMTID=1; Path=/")))
        assertNull(QrLoginProtocol.sessionCookie(listOf("MUSIC_U=; Path=/; Max-Age=0")))
        // 子串匹配会把下面这条当成登录成功（旧实现的 bug），按名字判定才是对的。
        assertNull(QrLoginProtocol.sessionCookie(listOf("XMUSIC_U=abc; Path=/")))
    }

    @Test
    fun `session cookie skips malformed and keeps values with equals`() {
        val cookie = QrLoginProtocol.sessionCookie(
            listOf("novalue; Path=/", "=bad; Path=/", "MUSIC_U=aa==; Path=/", "  __csrf=b  ; Path=/")
        )

        assertTrue(cookie!!.contains("MUSIC_U=aa=="))
        assertTrue(cookie.contains("__csrf=b"))
    }
}
