package com.chyi.alog

internal object CallerLocator {
    fun locate(): Pair<String, Int> {
        val stack = Throwable().stackTrace
        for (el in stack) {
            if (el.className.startsWith("com.chyi.alog") &&
                !el.className.startsWith("com.chyi.alog.sample")
            ) {
                continue
            }
            val file = el.fileName ?: el.className.substringAfterLast('.')
            return file to el.lineNumber
        }
        return "" to 0
    }

    fun stack(depth: Int): String {
        val stack = Throwable().stackTrace.filterNot {
            it.className.startsWith("com.chyi.alog") &&
                !it.className.startsWith("com.chyi.alog.sample")
        }
        return stack.take(depth).joinToString("\n") { "at $it" }
    }
}
