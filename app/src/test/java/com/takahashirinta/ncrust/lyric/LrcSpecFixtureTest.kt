package com.takahashirinta.ncrust.lyric

import com.google.gson.JsonParser
import com.takahashirinta.ncrust.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LrcSpecFixtureTest {
    @Test
    fun `parser matches shared lrc fixtures`() {
        val cases = JsonParser.parseString(Fixtures.readText("spec/fixtures/lrc/lrc.json")).asJsonArray
        var checked = 0
        for (element in cases) {
            val case = element.asJsonObject
            // 合并在 LyricsView 中，本测试只对照解析器的真实入口。
            if (case.get("kind").asString != "parse") continue
            val expected = case.getAsJsonObject("expect").getAsJsonArray("lines").map {
                val line = it.asJsonObject
                LrcLine(line.get("timeMs").asLong, line.get("text").asString)
            }

            assertEquals(
                case.get("id").asString,
                expected,
                LrcParser.parse(case.getAsJsonObject("input").get("lrc").asString)
            )
            checked++
        }
        assertTrue("No LRC parse fixtures were checked", checked > 0)
    }
}
