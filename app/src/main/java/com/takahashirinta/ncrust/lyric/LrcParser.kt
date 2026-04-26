package com.takahashirinta.ncrust.lyric

data class LrcLine(
    val timeMs: Long,
    val text: String
)

object LrcParser {
    fun parse(lrcText: String): List<LrcLine> {
        val lines = mutableListOf<LrcLine>()
        val regex = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\](.*)""")

        for (line in lrcText.lines()) {
            val match = regex.find(line.trim()) ?: continue
            val min = match.groupValues[1].toLong()
            val sec = match.groupValues[2].toLong()
            var ms = match.groupValues[3].toLong()
            if (match.groupValues[3].length == 2) ms *= 10
            val timeMs = min * 60000 + sec * 1000 + ms
            val text = match.groupValues[4].trim()
            if (text.isNotEmpty()) {
                lines.add(LrcLine(timeMs, text))
            }
        }

        return lines.sortedBy { it.timeMs }
    }
}