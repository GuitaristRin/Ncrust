package com.takahashirinta.ncrust.network

import com.google.gson.GsonBuilder
import com.takahashirinta.ncrust.auth.QrLoginProtocol
import com.takahashirinta.ncrust.player.PlayReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * os=osx 身份头的单测：字段、字段顺序、Cookie 拼法、加密信封形态。
 *
 * `os=osx` 是播放上报能不能入账的**决定性变量**（依据参考实现 `util/request.js` 的 eapi 分支与
 * `NeteaseCloudMusicApiEnhanced/api-enhanced#167`，协议见 `spec/api/endpoints.md`）：
 * 换成 android / iphone / pc 身份，请求照回 200 但不入账。字段顺序与拼法同样逐项对照。
 *
 * 边界：真正把信封序列化成 JSON 的是 `EapiCrypto` 里的 `org.json.JSONObject`，而本地单测里
 * `org.json` 是空桩（见 `QrLoginProtocolTest` 的说明）。这里用 Gson 渲染同一份 Map 断言字段顺序与
 * 原生类型，**「org.json 也这么写」是看代码得出的**；端到端那一步由 Windows Core 测试
 * （解真请求体）覆盖。
 */
class RetrofitClientOsxHeaderTest {

    private val deviceId = "0123456789abcdef0123456789abcdef"
    private val nowMs = 1_700_000_000_000L

    @Test
    fun `osx header has reference field order and values`() {
        val header = RetrofitClient.osxHeader(
            deviceId = deviceId,
            csrf = "CSRF",
            musicU = "MUSIC-U",
            musicA = null,
            nowMs = nowMs,
            requestSuffix = 42
        )

        assertEquals(
            listOf(
                "osver", "deviceId", "os", "appver", "versioncode", "mobilename",
                "buildver", "resolution", "__csrf", "channel", "requestId", "MUSIC_U"
            ),
            header.keys.toList()
        )
        assertEquals("15.5", header["osver"])
        assertEquals(deviceId, header["deviceId"])
        assertEquals("osx", header["os"])
        assertEquals("3.1.10.5100", header["appver"])
        assertEquals("140", header["versioncode"])
        assertEquals("", header["mobilename"])
        // buildver 是毫秒时间戳前 10 位 == 秒级时间戳
        assertEquals("1700000000", header["buildver"])
        assertEquals("1920x1080", header["resolution"])
        assertEquals("CSRF", header["__csrf"])
        assertEquals("netease", header["channel"])
        assertEquals("1700000000000_0042", header["requestId"])
        assertEquals("MUSIC-U", header["MUSIC_U"])
    }

    @Test
    fun `request id suffix is padded to four digits`() {
        val header = RetrofitClient.osxHeader(deviceId, "", null, null, nowMs = 1L, requestSuffix = 7)

        assertEquals("1_0007", header["requestId"])
    }

    @Test
    fun `music u and music a are appended only when present`() {
        val without = RetrofitClient.osxHeader(deviceId, "", null, null, nowMs, 0)
        assertFalse(without.containsKey("MUSIC_U"))
        assertFalse(without.containsKey("MUSIC_A"))

        val withBoth = RetrofitClient.osxHeader(deviceId, "", "U", "A", nowMs, 0)
        assertEquals(listOf("MUSIC_U", "MUSIC_A"), withBoth.keys.toList().takeLast(2))
    }

    @Test
    fun `cookie header encodes key and value pair wise`() {
        val header = RetrofitClient.osxHeader(deviceId, "", null, null, nowMs, 0)

        assertEquals(
            "osver=15.5; deviceId=$deviceId; os=osx; appver=3.1.10.5100; versioncode=140; " +
                "mobilename=; buildver=1700000000; resolution=1920x1080; __csrf=; " +
                "channel=netease; requestId=1700000000000_0000",
            QrLoginProtocol.cookieHeader(header)
        )
    }

    @Test
    fun `envelope keeps logs then e_r then header`() {
        val header = RetrofitClient.osxHeader(deviceId, "CSRF", "MUSIC-U", null, nowMs, 1)
        val payload = QrLoginProtocol.envelope(
            linkedMapOf<String, Any>("logs" to PlayReporter.startplayLogs(123)),
            header
        )

        assertEquals(listOf("logs", "e_r", "header"), payload.keys.toList())
        assertEquals(false, payload["e_r"])
        assertEquals(header, payload["header"])
    }

    @Test
    fun `envelope serialises logs as an escaped json string`() {
        val header = RetrofitClient.osxHeader(deviceId, "", null, null, nowMs, 0)
        val payload = QrLoginProtocol.envelope(
            linkedMapOf<String, Any>("logs" to PlayReporter.startplayLogs(123)),
            header
        )

        // logs 的值是**字符串**（JSON 文本被转义），不是嵌套对象：参考实现发的是
        // JSON.stringify([...]) 的结果，服务端按字符串再 parse 一次。
        // 这里的 Gson 关掉 HTML 转义，与 `PlayReporter` 构造 logs 时同一套配置
        // （默认配置会把 content 里的 `=` 写成 `\u003d`，与参考请求体不同形）。
        val plain = GsonBuilder().disableHtmlEscaping().create()
        assertEquals(
            "{\"logs\":\"[{\\\"action\\\":\\\"startplay\\\",\\\"json\\\":{\\\"id\\\":123," +
                "\\\"type\\\":\\\"song\\\",\\\"mainsite\\\":\\\"1\\\",\\\"mainsiteWeb\\\":\\\"1\\\"," +
                "\\\"content\\\":\\\"id=\\\"}}]\",\"e_r\":false,\"header\":" +
                plain.toJson(header) + "}",
            plain.toJson(payload)
        )
    }
}
