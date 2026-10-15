package com.takahashirinta.ncrust.walkman

import com.takahashirinta.ncrust.walkman.quality.QualityLadder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `spec/fixtures/quality/quality.json` 的全部用例。
 *
 * 任务书要求跑 `ladder` 与 `retry` 两类；`preference` 类同在一个夹具里，顺手一起跑
 * （同一个纯函数，漏跑只会让覆盖率凭空少一块）。
 */
class QualityFixtureTest {

    @Test
    fun ladderAndRetryAndPreference() {
        val cases = Fixtures.readArray("quality/quality.json")
        assertTrue("quality.json 里没有用例", cases.size() > 0)

        val failures = ArrayList<String>()
        val seenKinds = HashSet<String>()

        for (element in cases) {
            val case = element.asJsonObject
            val id = case.get("id").asString
            val kind = case.get("kind").asString
            seenKinds += kind
            val input = case.getAsJsonObject("input")
            val expect = case.getAsJsonObject("expect")
            try {
                when (kind) {
                    "ladder" -> {
                        val actual = QualityLadder.levelsFor(
                            level = input.get("level").asString,
                            supportsFlac = input.get("supportsFlac").asBoolean
                        )
                        val expected = expect.getAsJsonArray("levels").map { it.asString }
                        assertEquals(expected, actual)
                    }

                    "retry" -> {
                        val actual = QualityLadder.retryNext(input.get("level").asString)
                        val expected = expect.get("nextLevel").let { if (it.isJsonNull) null else it.asString }
                        assertEquals(expected, actual)
                    }

                    "preference" -> {
                        val actual = QualityLadder.levelForPreference(input.get("index").asInt)
                        assertEquals(expect.get("level").asString, actual)
                    }

                    else -> throw AssertionError("未实现的 quality 用例类型: $kind")
                }
            } catch (t: Throwable) {
                failures += "$id: ${t.message}"
            }
        }

        // 任务书点名了这两类；夹具日后新增/改名时要在这里炸，而不是悄悄少跑一类。
        assertTrue("quality.json 缺 ladder 类用例", seenKinds.contains("ladder"))
        assertTrue("quality.json 缺 retry 类用例", seenKinds.contains("retry"))

        if (failures.isNotEmpty()) {
            throw AssertionError("quality/quality.json 有 ${failures.size} 条用例不通过:\n" + failures.joinToString("\n"))
        }
    }
}
