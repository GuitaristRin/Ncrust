package com.takahashirinta.ncrust.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 版本号解析与比较。用例与 Windows 的 `AppVersion_TryParse` 一一对应——两端必须给出
 * 同一把尺子，否则同一份资产名在两端会挑出不同版本（跨端夹具
 * `spec/fixtures/update/releases.json` 的 `update.version.segment-count` 用例）。
 */
class AppVersionTest {

    private fun parsed(text: String?): AppVersion? = AppVersion.parseOrNull(text)

    @Test
    fun `parses one to four segments`() {
        assertEquals(AppVersion(2), parsed("2"))
        assertEquals(AppVersion(1, 2), parsed("1.2"))
        assertEquals(AppVersion(1, 0, 1), parsed("1.0.1"))
        assertEquals(AppVersion(1, 0, 1, 0), parsed("1.0.1.0"))
    }

    @Test
    fun `allows a v prefix`() {
        assertEquals(AppVersion(1, 0, 1), parsed("v1.0.1"))
        assertEquals(AppVersion(1, 0, 1), parsed("V1.0.1"))
    }

    @Test
    fun `trims surrounding whitespace like the windows implementation`() {
        // Windows 的 AppVersion.TryParse 先 Trim 整个字符串，两端必须一致。
        assertEquals(AppVersion(1, 0, 0, 0), parsed(" 1.0 "))
        assertNull("段内部有空白仍判非法", parsed("1. 0"))
        assertNull(parsed("1 .0"))
    }

    @Test
    fun `rejects malformed versions`() {
        assertNull(parsed(null))
        assertNull(parsed(""))
        assertNull(parsed("   "))
        assertNull(parsed("a.b"))
        assertNull(parsed("1..0"))
        assertNull(parsed("-1.0"))
        assertNull(parsed("+1.0"))
        assertNull(parsed("1.0.1.0.5"))
        assertNull(parsed("v"))
        // 溢出 int 的段：Windows 的 int.TryParse 同样失败，不能悄悄 wrap 成负数。
        assertNull(parsed("99999999999.0"))
    }

    @Test
    fun `compares segment by segment not lexicographically`() {
        assertTrue(AppVersion(1, 10, 0) > AppVersion(1, 9, 9))
        assertTrue(AppVersion(1, 9, 10) > AppVersion(1, 9, 9))
        assertTrue(AppVersion(1, 0, 1, 1) > AppVersion(1, 0, 1))
        assertFalse(AppVersion(1, 3, 2) > AppVersion(1, 3, 2))
        assertTrue(AppVersion(2) > AppVersion(1, 99, 99))
    }

    @Test
    fun `missing segments count as zero`() {
        assertEquals(AppVersion(1, 0, 0, 0), parsed("1"))
        assertEquals(0, parsed("1.0.0.0")!!.compareTo(parsed("1")!!))
    }

    @Test
    fun `display form is three segments`() {
        assertEquals("1.10.0", AppVersion(1, 10, 0, 7).toString())
        assertEquals("1.3.2", parsed("1.3.2")!!.toString())
    }

    @Test
    fun `storage form round trips all four segments`() {
        // 「稍后」记的是这个字符串：丢了第四段会让比较结果偏小，圆点又冒出来。
        val version = AppVersion(1, 4, 0, 1)
        assertEquals("1.4.0.1", version.storageString())
        assertEquals(version, parsed(version.storageString()))
        assertEquals(AppVersion(1, 3, 2), parsed(AppVersion(1, 3, 2).storageString()))
    }
}
