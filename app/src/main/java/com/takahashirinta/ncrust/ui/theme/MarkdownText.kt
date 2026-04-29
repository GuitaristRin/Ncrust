package com.takahashirinta.ncrust.ui.theme

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider

/**
 * 简易 Markdown 渲染器。
 *
 * 支持语法：
 * - # 标题（h1～h3）
 * - **粗体**
 * - *斜体*
 * - `代码`
 * - ![描述](图像URL)
 *
 * 不支持嵌套样式、链接、列表等高级特性。
 */
@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        markdown.lines().forEach { line ->
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
                    HorizontalDivider(color = Color(0xFF2A2A2A))
                    Spacer(Modifier.height(8.dp))
                }
                // 标题
                line.trimStart().startsWith("### ") -> {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = line.trimStart().removePrefix("### "),
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                }
                line.trimStart().startsWith("## ") -> {
                    Spacer(Modifier.height(20.dp))
                    Text(
                        text = line.trimStart().removePrefix("## "),
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                }
                line.trimStart().startsWith("# ") -> {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = line.trimStart().removePrefix("# "),
                        color = Color(0xFF1DB954),
                        fontSize = 28.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                    Spacer(Modifier.height(8.dp))
                }
                // 无序列表：- item
                line.trimStart().startsWith("- ") -> {
                    Row(modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)) {
                        Text("•", color = Color(0xFF1DB954), fontSize = 16.sp)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = parseInlineMarkdown(line.trimStart().removePrefix("- ")),
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 16.sp,
                            lineHeight = 24.sp
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
                    Text(
                        text = parseInlineMarkdown(line),
                        color = Color.White.copy(alpha = 0.85f),
                        fontSize = 16.sp,
                        lineHeight = 24.sp
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }        }
    }
}

private fun parseInlineMarkdown(line: String) = buildAnnotatedString {
    var i = 0
    while (i < line.length) {
        when {
            // **bold**
            line.startsWith("**", i) -> {
                val end = line.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) {
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
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic, color = Color.White)) {
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
                    withStyle(SpanStyle(background = Color(0xFF2A2A2A), color = Color(0xFF1DB954))) {
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
