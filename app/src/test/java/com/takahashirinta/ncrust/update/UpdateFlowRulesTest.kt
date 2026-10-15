package com.takahashirinta.ncrust.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * 更新流程里那些**纯规则**的单测：恢复落盘结果、把全局下载状态收窄到本页目标、
 * Content-Range 解析、续传元数据编解码、分页 Link 解析、合并多页后的判定。
 *
 * 这些逻辑是几个真实缺陷的修复点（升级后仍提示更新、装错包、坏前缀拼接、
 * 第 21 个以后的资产漏检、解析失败被当成「已最新」清空结果），所以单独锁住。
 */
class UpdateFlowRulesTest {

    private fun info(
        version: String,
        assetId: String? = null,
        url: String = "https://example.invalid/$version.apk"
    ) = UpdateInfo(
        version = AppVersion.parseOrNull(version)!!,
        assetName = "ncrust-android-universal-$version.apk",
        assetUrl = url,
        assetSize = 100L,
        releaseTag = "CrateStack-20261015A",
        releasePageUrl = "https://example.invalid/tag",
        publishedAt = "2026-10-15T09:30:00Z",
        body = null,
        assetId = assetId
    )

    // ---------- 恢复落盘结果（升级后重启不得再提示「更新到当前版本」） ----------

    @Test
    fun `restore keeps only a strictly higher found version`() {
        val candidate = info("1.4.0")
        assertEquals(candidate, UpdateRecovery.restore(candidate, AppVersion(1, 3, 2)))
        assertNull("与当前相同必须丢弃", UpdateRecovery.restore(candidate, AppVersion(1, 4, 0)))
        assertNull("低于当前必须丢弃", UpdateRecovery.restore(candidate, AppVersion(1, 5, 0)))
        assertNull("没有落盘结果", UpdateRecovery.restore(null, AppVersion(1, 3, 2)))
        assertNull("当前版本解析失败时宁可不动", UpdateRecovery.restore(candidate, null))
    }

    // ---------- 稳定目标标识 / 页面按目标门控 ----------

    @Test
    fun `target key prefers the asset id and falls back to the url`() {
        assertEquals("42", info("1.4.0", assetId = "42").targetKey)
        assertEquals(
            "https://example.invalid/1.4.0.apk",
            info("1.4.0").targetKey
        )
    }

    @Test
    fun `matches target compares the stable identity`() {
        assertTrue(info("1.4.0", assetId = "42").matchesTarget(info("1.4.0", assetId = "42")))
        assertFalse(info("1.4.0", assetId = "42").matchesTarget(info("1.4.0", assetId = "43")))
    }

    @Test
    fun `for target hides ready downloading and failed states of other targets`() {
        val file = Files.createTempFile("ncrust-flow", ".apk").toFile().apply { writeText("apk") }
        val mine = info("1.4.0", assetId = "mine")
        try {
            val ready = DownloadState.Ready(file, mine)
            assertEquals(ready, ready.forTarget("mine"))
            assertEquals(DownloadState.Idle, ready.forTarget("other"))
            assertEquals(
                "文件不在了也不能认",
                DownloadState.Idle,
                DownloadState.Ready(file.apply { delete() }, mine).forTarget("mine")
            )

            val downloading = DownloadState.Downloading(10L, 100L, "mine")
            assertEquals(downloading, downloading.forTarget("mine"))
            assertEquals(DownloadState.Idle, downloading.forTarget("other"))

            assertEquals(DownloadState.Verifying("mine"), DownloadState.Verifying("mine").forTarget("mine"))
            assertEquals(DownloadState.Idle, DownloadState.Verifying("mine").forTarget("other"))

            val failed = DownloadState.Failed(FailureReason.NETWORK, "mine")
            assertEquals(failed, failed.forTarget("mine"))
            assertEquals(DownloadState.Idle, failed.forTarget("other"))

            assertEquals(DownloadState.Idle, DownloadState.Idle.forTarget("mine"))
        } finally {
            file.delete()
        }
    }

    // ---------- Content-Range 解析 ----------

    @Test
    fun `parses content range start end and total`() {
        assertEquals(ContentRange(400, 999, 1000), parseContentRange("bytes 400-999/1000"))
        assertEquals(ContentRange(400, 999, 1000), parseContentRange("  bytes 400-999/1000  "))
        assertEquals(ContentRange(0, 99, -1), parseContentRange("bytes 0-99/*"))
    }

    @Test
    fun `rejects malformed content range`() {
        assertNull(parseContentRange(null))
        assertNull(parseContentRange(""))
        assertNull(parseContentRange("bytes 400-999"))
        assertNull(parseContentRange("items 400-999/1000"))
        assertNull(parseContentRange("bytes abc-999/1000"))
    }

    // ---------- 续传元数据 ----------

    @Test
    fun `resume marker round trips through encode and decode`() {
        val marker = ResumeMarker("https://example.invalid/a.apk", "asset-9", 1234L, "W/\"abc\"")
        assertEquals(marker, ResumeMarker.decode(marker.encode()))
    }

    @Test
    fun `resume marker decode rejects incomplete or wrong version text`() {
        assertNull(ResumeMarker.decode(null))
        assertNull(ResumeMarker.decode(""))
        assertNull(ResumeMarker.decode("version=2\nurl=u\nidentity=i\nsize=1\netag="))
        assertNull(ResumeMarker.decode("version=1\nidentity=i\nsize=1\netag="))
        assertNull(ResumeMarker.decode("version=1\nurl=u\nsize=1\netag="))
    }

    @Test
    fun `resume marker matches only the same identity and consistent size`() {
        val marker = ResumeMarker("u", "id", 1000L, null)
        assertTrue(marker.matches("u", "id", 1000L))
        assertTrue("一方不知道总长时不作为否决条件", marker.matches("u", "id", 0L))
        assertFalse(marker.matches("u2", "id", 1000L))
        assertFalse(marker.matches("u", "id2", 1000L))
        assertFalse(marker.matches("u", "id", 999L))
    }

    // ---------- 分页 Link 解析 ----------

    @Test
    fun `next page url follows the rel next link`() {
        val header =
            "<https://api.github.com/repositories/1/releases?page=2>; rel=\"next\", " +
                "<https://api.github.com/repositories/1/releases?page=5>; rel=\"last\""
        assertEquals(
            "https://api.github.com/repositories/1/releases?page=2",
            nextPageUrl(header)
        )
    }

    @Test
    fun `next page url is null without a next link`() {
        assertNull(nextPageUrl(null))
        assertNull(nextPageUrl(""))
        assertNull(nextPageUrl("<https://api.github.com/x?page=5>; rel=\"last\""))
    }

    // ---------- 合并多页与「解析失败不是已最新」 ----------

    private fun release(tag: String, name: String, size: Long) =
        """{"tag_name":"$tag","html_url":"https://example.invalid/$tag","draft":false,""" +
            """"prerelease":false,"published_at":"2026-10-15T09:30:00Z","body":"$tag",""" +
            """"assets":[{"name":"$name","browser_download_url":"https://example.invalid/$name","size":$size}]}"""

    @Test
    fun `evaluate releases takes the highest across all pages`() {
        val page1 = "[${release("CrateStack-20261015A", "ncrust-android-universal-1.4.0.apk", 100L)}]"
        val page2 = "[${release("CrateStack-20261016A", "ncrust-android-universal-1.5.0.apk", 200L)}]"

        val outcome = evaluateReleases(listOf(page1, page2), AppVersion(1, 3, 2))
        assertTrue(outcome is CheckOutcome.Found)
        assertEquals(AppVersion(1, 5, 0), (outcome as CheckOutcome.Found).info.version)
    }

    @Test
    fun `evaluate releases reports up to date when nothing is higher`() {
        val page = "[${release("CrateStack-20260928A", "ncrust-android-universal-1.3.2.apk", 100L)}]"
        assertEquals(CheckOutcome.UpToDate, evaluateReleases(listOf(page), AppVersion(1, 3, 2)))
    }

    @Test
    fun `evaluate releases returns null on unparseable pages so it is never treated as up to date`() {
        val current = AppVersion(1, 3, 2)
        assertNull(evaluateReleases(listOf("not json"), current))
        assertNull(evaluateReleases(listOf("{}"), current))
        assertNull(evaluateReleases(listOf(""), current))
        assertNull(evaluateReleases(listOf("[{}]", "garbage"), current))
        assertNull("当前版本解析失败也判为不可判定", evaluateReleases(listOf("[{}]"), null))
    }

    // ---------- UpdateChecker 解析资产 id ----------

    @Test
    fun `checker keeps the asset id as the stable identity`() {
        val json = """[{"tag_name":"t","html_url":"h","draft":false,"prerelease":false,
            |"published_at":"p","body":"b","assets":[
            |{"id":98765,"name":"ncrust-android-universal-1.4.0.apk",
            |"browser_download_url":"https://example.invalid/1.4.0.apk","size":10}]}]""".trimMargin()
        val found = UpdateChecker.find(json, AppVersion(1, 3, 2))
        assertEquals("98765", found?.assetId)
        assertEquals("98765", found?.targetKey)
    }
}
