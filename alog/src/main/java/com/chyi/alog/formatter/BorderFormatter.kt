package com.chyi.alog.formatter

object BorderFormatter {
    private const val TOP = "┌────────────────────────────────────────────────────────"
    private const val DIV = "├────────────────────────────────────────────────────────"
    private const val BOT = "└────────────────────────────────────────────────────────"
    private const val PREFIX = "│ "

    fun format(sections: List<String>): String {
        val sb = StringBuilder()
        sb.append(TOP).append('\n')
        sections.forEachIndexed { index, section ->
            section.split('\n').forEach { line ->
                sb.append(PREFIX).append(line).append('\n')
            }
            if (index != sections.lastIndex) sb.append(DIV).append('\n')
        }
        sb.append(BOT)
        return sb.toString()
    }
}
