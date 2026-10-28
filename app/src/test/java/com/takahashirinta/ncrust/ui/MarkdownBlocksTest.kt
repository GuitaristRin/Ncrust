package com.takahashirinta.ncrust.ui

import com.takahashirinta.ncrust.ui.theme.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Assert.*
import org.junit.Test

class MarkdownBlocksTest {
    @Test fun `release download table becomes aligned cells rather than literal pipes`() {
        val table = parseMarkdownBlocks("""
            | 平台 | 版本 | 下载 |
            |---|:---:|---:|
            | Android | 1.4.1 | [APK](https://example.org/app.apk) |
            | Windows | 1.0.3 | `.msix` |
        """.trimIndent()).single() as MarkdownBlock.Table
        assertEquals(listOf("平台", "版本", "下载"), table.header)
        assertEquals(listOf(CellAlignment.START, CellAlignment.CENTER, CellAlignment.END), table.alignment)
        assertEquals(2, table.rows.size)
        assertEquals("[APK](https://example.org/app.apk)", table.rows[0][2])
    }

    @Test fun `outer pipes are optional and empty cells retain their columns`() {
        val table = parseMarkdownBlocks("A | B | C\n- | - | -\n1 || 3").single() as MarkdownBlock.Table
        assertEquals(listOf("1", "", "3"), table.rows.single())
    }

    @Test fun `escaped pipes including those in inline code stay inside a cell`() {
        assertEquals(listOf("a|b", "`x|y`"), splitMarkdownRow("| a\\|b | `x\\|y` |"))
        assertEquals(listOf("a|"), splitMarkdownRow("a\\|"))
        assertEquals(listOf("a\\", "b"), splitMarkdownRow("a\\\\|b"))
    }

    @Test fun `short rows are padded and excess cells ignored per GFM`() {
        val table = parseMarkdownBlocks("A|B\n---|---\n|one|\nx|y|z").single() as MarkdownBlock.Table
        assertEquals(listOf(listOf("one", ""), listOf("x", "y")), table.rows)
    }

    @Test fun `blank line terminates table and keeps following release heading`() {
        val blocks = parseMarkdownBlocks("A|B\n---|---\n1|2\n\n## 修复")
        assertTrue(blocks[0] is MarkdownBlock.Table)
        assertEquals(MarkdownBlock.Line("## 修复"), blocks.last())
    }

    @Test fun `ordinary pipe text and mismatched delimiter remain literal`() {
        assertTrue(parseMarkdownBlocks("a|b\n---|---|---").all { it is MarkdownBlock.Line })
        assertTrue(parseMarkdownBlocks("a|b\nnot|a delimiter").all { it is MarkdownBlock.Line })
    }

    @Test fun `empty pipe lines cannot create a zero column table`() {
        assertTrue(parseMarkdownBlocks("|\n|").all { it is MarkdownBlock.Line })
    }

    @Test fun `fenced code is preserved without interpreting tables or inline styles`() {
        val blocks = parseMarkdownBlocks("```markdown\na|b\n---|---\n**literal**\n```\nend")
        assertEquals(MarkdownBlock.Code("a|b\n---|---\n**literal**"), blocks[0])
        assertEquals(MarkdownBlock.Line("end"), blocks[1])
    }

    @Test fun `longer tilde fences do not close on a shorter fence`() {
        assertEquals(MarkdownBlock.Code("~~~\ncode"),
            parseMarkdownBlocks("~~~~\n~~~\ncode\n~~~~").single())
    }

    @Test fun `download links have a clickable annotation and styled label`() {
        val text = parseInlineMarkdown("[`APK`](https://example.org/app.apk)", Color.White, Color.Black)
        assertEquals("APK", text.text)
        val link = text.getLinkAnnotations(0, text.length).single().item as LinkAnnotation.Url
        assertEquals("https://example.org/app.apk", link.url)
    }

    @Test fun `unsupported link schemes remain text`() {
        val text = parseInlineMarkdown("[x](javascript:alert)", Color.White, Color.Black)
        assertEquals("[x](javascript:alert)", text.text)
        assertTrue(text.getLinkAnnotations(0, text.length).isEmpty())
    }
}
