package com.chyi.alog.decode.gs

/**
 * Annotate legacy txt lines that contain GS UART hex dumps.
 * Keeps original lines; appends indented decode lines.
 */
object GsSerialAnnotator {
    private val SEND_RECV = Regex("""(发送|接收)[:：]\s*([0-9A-Fa-f][0-9A-Fa-f\s]*)\s*$""")
    private val RECEIVED_MSG = Regex("""_收到消息[:：]\s*([0-9A-Fa-f]+)\s*$""")
    private val PURE_HEX = Regex("""^[0-9A-Fa-f][0-9A-Fa-f\s]*$""")

    fun annotate(legacyTxt: String): String {
        if (legacyTxt.isEmpty()) return legacyTxt
        val endsWithNl = legacyTxt.endsWith("\n")
        val lines = legacyTxt.split('\n').let { if (endsWithNl && it.last().isEmpty()) it.dropLast(1) else it }
        val annotated = annotateLines(lines)
        if (annotated.isEmpty()) return if (endsWithNl) "\n" else ""
        return annotated.joinToString("\n", postfix = "\n")
    }

    fun annotateLines(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()
        val out = ArrayList<String>(lines.size)
        for (line in lines) {
            out += line
            for (n in annotateLine(line)) {
                out += "  $n"
            }
        }
        return out
    }

    private fun annotateLine(line: String): List<String> {
        val msg = extractMsg(line) ?: return emptyList()
        val extracted = extractHex(msg) ?: return emptyList()
        val (directionHint, hexRaw) = extracted
        val bytes = GsHex.parse(hexRaw) ?: return emptyList()
        val frames = GsFrame.scan(bytes)
        if (frames.isEmpty()) {
            // had hex but no AA55/A55A frame
            val ascii = bytes.map {
                val c = it.toInt() and 0xFF
                if (c in 0x20..0x7E) c.toChar() else '.'
            }.joinToString("")
            return listOf("非高盛帧 ASCII=$ascii HEX=${GsHex.toSpaced(bytes)}")
        }
        val result = mutableListOf<String>()
        for (frame in frames) {
            val decoded = GsFrameDecoder.decode(frame)
            // Override direction label in header if log text specifies send/recv
            if (directionHint != null && decoded.isNotEmpty()) {
                val dir = directionHint
                val rest = decoded[0].replace(Regex("""^\[(发送|接收)]"""), "[$dir]")
                result += rest
                result += decoded.drop(1)
            } else {
                result += decoded
            }
        }
        return result
    }

    private fun extractMsg(line: String): String? {
        // legacy: "YYYY-MM-dd HH:mm:ss.SSS tag:msg"
        val m = Regex("""^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3} [^:]+:(.*)$""").matchEntire(line)
        if (m != null) return m.groupValues[1]
        // tests may use short prefix "t tag:msg"
        val colon = line.indexOf(':')
        if (colon >= 0 && colon < line.length - 1) return line.substring(colon + 1)
        return null
    }

    private fun extractHex(msg: String): Pair<String?, String>? {
        SEND_RECV.find(msg)?.let {
            return it.groupValues[1] to it.groupValues[2].trim()
        }
        RECEIVED_MSG.find(msg)?.let {
            return "接收" to it.groupValues[1].trim()
        }
        val trimmed = msg.trim()
        if (PURE_HEX.matches(trimmed) && (trimmed.contains("AA", true) || trimmed.contains("A5", true))) {
            return null to trimmed
        }
        return null
    }
}
