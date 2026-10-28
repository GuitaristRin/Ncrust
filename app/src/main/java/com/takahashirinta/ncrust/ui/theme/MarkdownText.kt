package com.takahashirinta.ncrust.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 简易 Markdown 渲染器。
 *
 * 支持语法：
 * - # 标题（h1～h3）
 * - **粗体**
 * - *斜体*
 * - `代码`
 * - ![描述](图像URL)、链接、列表、围栏代码块与 GFM 表格
 *
 * 不支持 HTML 与复杂嵌套样式。
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    Column(modifier = modifier) {
        blocks.forEach { block ->
            when (block) {
                is MarkdownBlock.Line -> MarkdownLine(block.text)
                is MarkdownBlock.Table -> MarkdownTable(block)
                is MarkdownBlock.Code -> BasicText(
                    block.text,
                    modifier = Modifier.fillMaxWidth()
                        .background(LocalMetroColors.current.surfaceVariant)
                        .padding(12.dp),
                    style = TextStyle(color = LocalMetroColors.current.onBackground,
                        fontSize = 14.sp, lineHeight = 21.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                )
            }
        }
    }
}

@Composable
private fun MarkdownLine(line: String) {
    val onBg = LocalMetroColors.current.onBackground
    val codeBg = LocalMetroColors.current.surfaceVariant
    when {
        // 图片：![alt](url)
        line.trimStart().startsWith("![") -> {
            val alt = line.substringAfter("![").substringBefore("]")
            val url = line.substringAfter("](").substringBefore(")")
            if (url.isNotBlank()) {
                AsyncImage(
                    model = url,
                    contentDescription = alt.ifBlank { null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center
                )
            }
        }
        // 水平分割线：--- 或 ***
        line.trimStart().matches(Regex("^[-*]{3,}$")) -> {
            Spacer(Modifier.height(8.dp))
            MetroDivider(color = codeBg)
            Spacer(Modifier.height(8.dp))
        }
        // 标题
        line.trimStart().startsWith("### ") -> {
            Spacer(Modifier.height(16.dp))
            MetroText(
                text = line.trimStart().removePrefix("### "),
                color = LocalMetroColors.current.onBackground,
                style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(8.dp))
        }
        line.trimStart().startsWith("## ") -> {
            Spacer(Modifier.height(20.dp))
            MetroText(
                text = line.trimStart().removePrefix("## "),
                color = LocalMetroColors.current.onBackground,
                style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(Modifier.height(8.dp))
        }
        line.trimStart().startsWith("# ") -> {
            Spacer(Modifier.height(24.dp))
            MetroText(
                text = line.trimStart().removePrefix("# "),
                color = LocalMetroColors.current.primary,
                style = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            )
            Spacer(Modifier.height(8.dp))
        }
        // 无序列表：- item
        line.trimStart().startsWith("- ") -> {
            Row(modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)) {
                MetroText("•", color = LocalMetroColors.current.primary, style = TextStyle(fontSize = 16.sp))
                Spacer(Modifier.width(8.dp))
                BasicText(
                    text = parseInlineMarkdown(line.trimStart().removePrefix("- "), onBg, codeBg),
                    style = TextStyle(
                        color = LocalMetroColors.current.onBackground.copy(alpha = 0.85f),
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    )
                )
            }
        }
        // 空行
        line.isBlank() -> {
            Spacer(Modifier.height(12.dp))
        }
        // 普通段落（含行内样式）
        else -> {
            Spacer(Modifier.height(4.dp))
            BasicText(
                text = parseInlineMarkdown(line, onBg, codeBg),
                style = TextStyle(
                    color = LocalMetroColors.current.onBackground.copy(alpha = 0.85f),
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                )
            )
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun MarkdownTable(table: MarkdownBlock.Table) {
    val colors = LocalMetroColors.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        // 整表共用横向滚动与列宽，长下载链接不会挤坏相邻列或让表头错位。
        val cellWidth = maxOf(144.dp, maxWidth / table.header.size)
        Column(Modifier.horizontalScroll(rememberScrollState())) {
            (listOf(table.header) + table.rows).forEachIndexed { rowIndex, cells ->
                Row(Modifier.background(if (rowIndex == 0) colors.surfaceVariant else Color.Transparent)) {
                    cells.forEachIndexed { column, cell ->
                        BasicText(
                            text = parseInlineMarkdown(cell, colors.onBackground, colors.surfaceVariant),
                            modifier = Modifier.width(cellWidth).padding(12.dp),
                            style = TextStyle(
                                color = colors.onBackground, fontSize = 14.sp, lineHeight = 22.sp,
                                fontWeight = if (rowIndex == 0) FontWeight.Bold else FontWeight.Normal,
                                textAlign = when (table.alignment[column]) {
                                    CellAlignment.START -> TextAlign.Start
                                    CellAlignment.CENTER -> TextAlign.Center
                                    CellAlignment.END -> TextAlign.End
                                }
                            )
                        )
                    }
                }
                MetroDivider(color = colors.surfaceVariant)
            }
        }
    }
}

internal fun parseInlineMarkdown(line: String, onBg: Color, codeBg: Color): androidx.compose.ui.text.AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < line.length) {
        when {
            line[i] == '[' -> {
                val labelEnd = line.indexOf("](", i + 1)
                val urlEnd = if (labelEnd >= 0) line.indexOf(')', labelEnd + 2) else -1
                val url = if (urlEnd >= 0) line.substring(labelEnd + 2, urlEnd) else ""
                if (url.startsWith("https://") || url.startsWith("http://")) {
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(SpanStyle(
                        color = onBg, textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline
                    )))) {
                        append(parseInlineMarkdown(line.substring(i + 1, labelEnd), onBg, codeBg))
                    }
                    i = urlEnd + 1
                } else {
                    append(line[i++])
                }
            }
            // **bold**
            line.startsWith("**", i) -> {
                val end = line.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = onBg)) {
                        append(line.substring(i + 2, end))
                    }
                    i = end + 2
                } else {
                    append(line[i])
                    i++
                }
            }
            // *italic*
            line.startsWith("*", i) -> {
                val end = line.indexOf("*", i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = onBg)) {
                        append(line.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(line[i])
                    i++
                }
            }
            // `code`
            line.startsWith("`", i) -> {
                val end = line.indexOf("`", i + 1)
                if (end != -1) {
                    withStyle(SpanStyle(background = codeBg, color = onBg)) {
                        append(line.substring(i + 1, end))
                    }
                    i = end + 1
                } else {
                    append(line[i])
                    i++
                }
            }
            else -> {
                append(line[i])
                i++
            }
        }
    }
}
