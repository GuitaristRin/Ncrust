package com.takahashirinta.ncrust.update

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.takahashirinta.ncrust.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `spec/fixtures/update/releases.json` 是跨端（Android / Windows）「挑更新」规则的权威答案
 * （schema 与规则见 `spec/fixtures/update/README.md`），期望值按规则手写。
 *
 * 这里逐条跑 Android 侧；`platforms` 里不含 android 的用例（Windows 专有）跳过并断言跳过名单，
 * 免得哪天平台标记写错、用例被静默吞掉还以为覆盖了。夹具用 Gson 解析
 * （本地单测里 org.json 是空桩）。
 */
class UpdateFixtureTest {

    private fun cases(): JsonArray =
        JsonParser.parseString(Fixtures.readText("spec/fixtures/update/releases.json")).asJsonArray

    @Test
    fun `android cases match spec fixture`() {
        val all = cases()
        var checked = 0
        val skipped = mutableListOf<String>()

        for (element in all) {
            val case = element.asJsonObject
            val id = case.get("id").asString
            val platforms = case.get("platforms")
                ?.takeIf { !it.isJsonNull }
                ?.asJsonArray
                ?.map { it.asString }
                ?: listOf("android", "windows")
            if ("android" !in platforms) {
                skipped += id
                continue
            }

            val input = case.getAsJsonObject("input")
            val current = AppVersion.parseOrNull(input.get("currentVersion").asString)
            assertNotNull("$id: 夹具里的 currentVersion 解析失败", current)
            // 夹具存的是结构化 JSON，UpdateChecker 收的是 API 原文，所以还原成文本喂进去。
            val releasesJson = input.getAsJsonArray("releases").toString()
            val info = UpdateChecker.find(
                releasesJson,
                current!!,
                input.get("platform").asString,
                input.get("arch").asString
            )

            val expect = case.get("expect")
            if (expect.isJsonNull) {
                assertNull("$id: 期望无更新", info)
            } else {
                val e: JsonObject = expect.asJsonObject
                assertNotNull("$id: 期望命中更新", info)
                assertEquals("$id: 版本", AppVersion.parseOrNull(e.get("version").asString), info!!.version)
                assertEquals("$id: 资产名", e.get("assetName").asString, info.assetName)
                assertEquals("$id: 资产 URL", e.get("assetUrl").asString, info.assetUrl)
                assertEquals("$id: 资产大小", e.get("assetSize").asLong, info.assetSize)
                assertEquals("$id: 班次 tag", e.get("releaseTag").asString, info.releaseTag)
                assertEquals("$id: release 页 URL", e.get("releasePageUrl").asString, info.releasePageUrl)
                assertEquals("$id: 发布日期", e.get("publishedAt").asString, info.publishedAt)
                assertEquals("$id: release 正文", e.get("body").asString, info.body)
            }
            checked++
        }

        // 每条用例要么跑了、要么在跳过名单里，不能被静默吞掉。
        assertEquals("用例总数与「跑了 + 跳过」不一致", all.size(), checked + skipped.size)
        assertTrue("跑到的 Android 用例太少：$checked", checked >= 12)
        assertEquals(
            "Android 跳过的应只有 Windows 专有用例（platforms 里没有 android）",
            listOf(
                "update.windows.highest-across-trains",
                "update.windows.same-version",
                "update.windows.train.higher-version"
            ),
            skipped.sorted()
        )
    }

    /** 夹具读不到时必须红（`Fixtures` 找不到就直接 AssertionError），不能静默跳过。 */
    @Test
    fun `fixture is reachable`() {
        assertTrue(cases().size() >= 15)
    }}
