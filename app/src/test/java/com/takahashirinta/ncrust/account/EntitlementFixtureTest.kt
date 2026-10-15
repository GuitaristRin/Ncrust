package com.takahashirinta.ncrust.account

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.takahashirinta.ncrust.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `spec/fixtures/entitlement/` 下的三份 json 是跨端（Android / Windows）账号与权益判定的权威答案，
 * 期望值由规格 `spec/api/entitlement.md` 手写。这里逐条跑，断言失败的消息里带用例 id。
 *
 * 夹具用 Gson 解析（本地单测里 org.json 是空桩），与线上链路同一套字段映射。
 */
class EntitlementFixtureTest {

    private val gson = Gson()

    private fun cases(path: String): JsonArray =
        JsonParser.parseString(Fixtures.readText(path)).asJsonArray

    private fun JsonElement?.asObjectOrNull(): JsonObject? =
        this?.takeIf { !it.isJsonNull }?.asJsonObject

    private fun JsonElement?.asStringOrNull(): String? =
        this?.takeIf { !it.isJsonNull }?.asString

    private fun JsonElement?.asIntOrNull(): Int? =
        this?.takeIf { !it.isJsonNull }?.asInt

    private fun JsonElement?.asLongOrNull(): Long? =
        this?.takeIf { !it.isJsonNull }?.asLong

    @Test
    fun `playback cases match spec fixture`() {
        val all = cases("spec/fixtures/entitlement/playback.json")
        var checked = 0
        for (element in all) {
            val case = element.asJsonObject
            val id = case.get("id").asString
            assertEquals("$id: kind", "playback", case.get("kind").asString)
            val input = case.getAsJsonObject("input")

            val item: PlaybackItem? = input.get("item").asObjectOrNull()
                ?.let { gson.fromJson(it, PlaybackItem::class.java) }
            val privilege: SongPrivilege? = input.get("privilege").asObjectOrNull()
                ?.let { gson.fromJson(it, SongPrivilege::class.java) }

            val actual = classifyPlayback(
                requestedLevel = input.get("requestedLevel").asString,
                sessionValid = input.get("sessionValid").asBoolean,
                item = item,
                privilege = privilege
            )
            val expect = case.getAsJsonObject("expect")
            val expectedKind = expect.get("kind").asString
            val expectReason = expect.get("reason").asStringOrNull()?.let { wire ->
                EntitlementReason.fromWire(wire)
                    ?: error("$id: 夹具里的 reason 不在枚举内: $wire")
            }

            when (actual) {
                is PlaybackEntitlement.Full -> {
                    assertEquals("$id: kind", "full", expectedKind)
                    assertEquals(
                        "$id: capped",
                        expect.get("capped").asBoolean,
                        actual.capped
                    )
                    assertEquals(
                        "$id: cappedTo",
                        expect.get("cappedTo").asStringOrNull(),
                        actual.cappedTo
                    )
                    assertEquals(
                        "$id: cappedReason",
                        expect.get("cappedReason").asStringOrNull()?.let { wire ->
                            EntitlementReason.fromWire(wire)
                        },
                        actual.cappedReason
                    )
                    // capped=false 时不该带 cappedTo / cappedReason（否则界面会显示一个假档位）。
                    if (!actual.capped) {
                        assertEquals("$id: 未封顶不应有 cappedTo", null, actual.cappedTo)
                        assertEquals("$id: 未封顶不应有 cappedReason", null, actual.cappedReason)
                    }
                }
                is PlaybackEntitlement.Trial -> {
                    assertEquals("$id: kind", "trial", expectedKind)
                    assertEquals("$id: trialSeconds", expect.get("trialSeconds").asInt, actual.seconds)
                    assertEquals("$id: reason", expectReason, actual.reason)
                }
                is PlaybackEntitlement.Unavailable -> {
                    assertEquals("$id: kind", "unavailable", expectedKind)
                    assertEquals("$id: reason", expectReason, actual.reason)
                }
            }
            checked++
        }
        // 一条都不许静默跳过：夹具里全是 playback 用例。
        assertEquals("夹具用例数应全部被检查", all.size(), checked)
        assertTrue("夹具应至少有 12 条 playback 用例，实际 $checked 条", checked >= 12)
    }

    @Test
    fun `vip cases match spec fixture`() {
        val all = cases("spec/fixtures/entitlement/vip.json")
        var checked = 0
        for (element in all) {
            val case = element.asJsonObject
            val id = case.get("id").asString
            assertEquals("$id: kind", "vip", case.get("kind").asString)
            val input = case.getAsJsonObject("input")
            val response: VipInfoResponse? = input.get("response").asObjectOrNull()
                ?.let { gson.fromJson(it, VipInfoResponse::class.java) }

            // 夹具里的 now 全部由响应自带；这里传一个与夹具无关的本机时间，
            // 若实现错误地优先用本机时间，用例会立刻红。
            val actual = deriveVip(response, localNow = 0L)
            val expect = case.getAsJsonObject("expect")
            val expectedState = expect.get("state").asString
            val actualState = when (actual) {
                is VipState.Active -> "active"
                is VipState.Lapsed -> "lapsed"
                VipState.None -> "none"
                VipState.Unknown -> "unknown"
            }
            assertEquals("$id: state", expectedState, actualState)
            expect.get("kind").asStringOrNull()?.let { expectedKind ->
                val actualKind = when (actual) {
                    is VipState.Active -> actual.kind.wire
                    is VipState.Lapsed -> actual.kind.wire
                    else -> null
                }
                assertEquals("$id: kind", expectedKind, actualKind)
            }
            expect.get("expireAt").asLongOrNull()?.let { expectedExpire ->
                val actualExpire = when (actual) {
                    is VipState.Active -> actual.expireAt
                    is VipState.Lapsed -> actual.expireAt
                    else -> null
                }
                assertEquals("$id: expireAt", expectedExpire, actualExpire)
            }
            checked++
        }
        assertEquals("夹具用例数应全部被检查", all.size(), checked)
        assertTrue("夹具应至少有 6 条 vip 用例，实际 $checked 条", checked >= 6)
    }

    @Test
    fun `session cases match spec fixture`() {
        val all = cases("spec/fixtures/entitlement/session.json")
        var checked = 0
        for (element in all) {
            val case = element.asJsonObject
            val id = case.get("id").asString
            assertEquals("$id: kind", "session", case.get("kind").asString)
            val input = case.getAsJsonObject("input")
            val accountGet: AccountGetResponse? = input.get("accountGet").asObjectOrNull()
                ?.let { gson.fromJson(it, AccountGetResponse::class.java) }

            val actual = deriveSession(
                hasCookie = input.get("hasCookie").asBoolean,
                accountGet = accountGet,
                otherApiCode = input.get("otherApiCode").asIntOrNull()
            )
            val expected = case.getAsJsonObject("expect").get("state").asString
            val actualState = when (actual) {
                SessionState.Unknown -> "unknown"
                SessionState.LoggedOut -> "logged-out"
                SessionState.Expired -> "expired"
                is SessionState.Valid -> "valid"
            }
            assertEquals("$id: state", expected, actualState)
            if (actual is SessionState.Valid) {
                val account = accountGet?.account
                assertEquals("$id: userId", account?.id, actual.userId)
                assertEquals("$id: nickname", accountGet?.profile?.nickname, actual.nickname)
            }
            checked++
        }
        assertEquals("夹具用例数应全部被检查", all.size(), checked)
        assertTrue("夹具应至少有 6 条 session 用例，实际 $checked 条", checked >= 6)
    }
}
