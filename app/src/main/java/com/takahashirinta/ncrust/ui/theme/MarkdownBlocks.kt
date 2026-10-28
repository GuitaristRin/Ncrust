package com.takahashirinta.ncrust.ui.theme

internal sealed interface MarkdownBlock {
    data class Line(val text: String) : MarkdownBlock
    data class Code(val text: String) : MarkdownBlock
    data class Table(
        val header: List<String>,
        val alignment: List<CellAlignment>,
        val rows: List<List<String>>
    ) : MarkdownBlock
}

internal enum class CellAlignment { START, CENTER, END }

/** 表头与分隔行必须匹配，避免把含竖线的普通正文误吞成表格。 */
internal fun parseMarkdownBlocks(markdown: String): List<MarkdownBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<MarkdownBlock>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        val fence = Regex("^ {0,3}(`{3,}|~{3,})(.*)$").matchEntire(line)
        if (fence != null) {
            val marker = fence.groupValues[1]
            val code = mutableListOf<String>()
            index++
            while (index < lines.size) {
                val closing = lines[index].trim()
                if (closing.length >= marker.length && closing.all { it == marker[0] }) {
                    index++
                    break
                }
                code += lines[index++]
            }
            blocks += MarkdownBlock.Code(code.joinToString("\n"))
            continue
        }
        val header = splitMarkdownRow(line)
        val delimiter = lines.getOrNull(index + 1)?.let(::splitMarkdownRow)
        if ('|' in line && header.isNotEmpty() && delimiter != null && header.size == delimiter.size &&
            delimiter.all { it.matches(Regex(":?-+:?")) }) {
            val alignment = delimiter.map {
                when {
                    it.startsWith(':') && it.endsWith(':') -> CellAlignment.CENTER
                    it.endsWith(':') -> CellAlignment.END
                    else -> CellAlignment.START
                }
            }
            val rows = mutableListOf<List<String>>()
            index += 2
            while (index < lines.size && lines[index].isNotBlank() && '|' in lines[index]) {
                val cells = splitMarkdownRow(lines[index++])
                rows += List(header.size) { cells.getOrElse(it) { "" } }
            }
            blocks += MarkdownBlock.Table(header, alignment, rows)
        } else {
            blocks += MarkdownBlock.Line(line)
            index++
        }
    }
    return blocks
}

/** GFM 的转义竖线（含代码内的 \|）属于单元格内容，不是分列符。 */
internal fun splitMarkdownRow(line: String): List<String> {
    val cells = mutableListOf<String>()
    val cell = StringBuilder()
    val text = line.trim()
    var index = 0
    var trailingSeparator = false
    while (index < text.length) {
        val char = text[index]
        if (char == '\\' && index + 1 < text.length && text[index + 1] in "\\|") {
            cell.append(text[index + 1])
            index += 2
            trailingSeparator = false
        } else if (char == '|') {
            cells += cell.toString().trim()
            cell.clear()
            index++
            trailingSeparator = true
        } else {
            cell.append(char)
            index++
            trailingSeparator = false
        }
    }
    cells += cell.toString().trim()
    if (text.startsWith('|')) cells.removeAt(0)
    if (trailingSeparator && cells.isNotEmpty()) cells.removeAt(cells.lastIndex)
    return cells
}
