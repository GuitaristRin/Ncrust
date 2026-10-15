package com.takahashirinta.ncrust.walkman

import com.google.gson.JsonObject
import com.takahashirinta.ncrust.walkman.api.Entitlement
import com.takahashirinta.ncrust.walkman.api.FreeTrialInfo
import com.takahashirinta.ncrust.walkman.api.Privilege
import com.takahashirinta.ncrust.walkman.api.SongUrlItem
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `spec/fixtures/entitlement/playback.json` 的全部用例。
 *
 * 比对方式是**只看 `expect` 里出现过的字段**，而不是把期望构造成一个 `Verdict` 再整体相等：
 * 夹具的语义是「这几个字段必须是这些值」，例如 `full` 用例只声明 `capped`，没有声明
 * `reason` —— 整体相等会把夹具没要求的东西也变成断言，日后加字段就会误报。
 */
class EntitlementFixtureTest {

    @Test
    fun classifyPlayback() {
        val cases = Fixtures.readArray("entitlement/playback.json")
        assertTrue("playback.json 里没有用例", cases.size() > 0)

        val failures = ArrayList<String>()
        for (element in cases) {
            val case = element.asJsonObject
            val id = case.get("id").asString
            val input = case.getAsJsonObject("input")
            val expect = case.getAsJsonObject("expect")
            try {
                val item = input.getAsJsonObject("item")
                val actual = Entitlement.classifyPlayback(
                    item = SongUrlItem(
                        id = item.get("id").asLong,
                        url = item.get("url").let { if (it.isJsonNull) null else it.asString },
                        code = item.get("code").asInt,
                        level = item.get("level").let { if (it.isJsonNull) null else it.asString },
                        fee = item.get("fee").asInt,
                        // 不能写成 getAsJsonObject：成员值是 JSON null 时 Gson 会抛
                        // IllegalStateException("Not a JSON Object: null")，而夹具里
                        // freeTrialInfo 为 null 是常态（完整音源那几条）。
                        freeTrialInfo = item.optObjectOrNull("freeTrialInfo")?.let {
                            FreeTrialInfo(start = it.get("start").asInt, end = it.get("end").asInt)
                        }
                    ),
                    requestedLevel = input.get("requestedLevel").asString,
                    privilege = input.privilege(),
                    sessionValid = input.get("sessionValid").asBoolean
                )

                check("kind", actual.kind.name.lowercase(), expect.get("kind").asString)
                expect.optIntOrNull("trialSeconds")?.let { check("trialSeconds", actual.trialSeconds, it) }
                expect.optStringOrNull("reason")?.let { check("reason", actual.reason?.name?.lowercase(), it) }
                expect.optBoolOrNull("capped")?.let { check("capped", actual.capped, it) }
                expect.optStringOrNull("cappedTo")?.let { check("cappedTo", actual.cappedTo, it) }
                expect.optStringOrNull("cappedReason")?.let {
                    check("cappedReason", actual.cappedReason?.name?.lowercase(), it)
                }
            } catch (t: Throwable) {
                failures += "$id: ${t.message}"
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError("entitlement/playback.json 有 ${failures.size} 条用例不通过:\n" + failures.joinToString("\n"))
        }
    }

    private fun check(field: String, actual: Any?, expected: Any?) {
        if (actual != expected) {
            throw AssertionError("$field: 期望 <$expected>，实际 <$actual>")
        }
    }

    private fun JsonObject.optObjectOrNull(name: String): JsonObject? =
        get(name)?.takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.privilege(): Privilege? {
        val o = optObjectOrNull("privilege") ?: return null
        return Privilege(id = 0, st = o.get("st").asInt, pl = o.get("pl").asLong, fee = o.get("fee").asInt)
    }

    private fun JsonObject.optIntOrNull(name: String): Int? =
        get(name)?.takeIf { !it.isJsonNull }?.asInt

    private fun JsonObject.optStringOrNull(name: String): String? =
        get(name)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.optBoolOrNull(name: String): Boolean? =
        get(name)?.takeIf { !it.isJsonNull }?.asBoolean
}
