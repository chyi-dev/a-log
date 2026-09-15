package com.chyi.alog.decode.gs

/**
 * Frame scanner for GS recipe-board UART protocol (GS_CF_PR_API_1.5F).
 *
 * Command: AA 55 LEN COMND DATA SUM
 * Response: A5 5A LEN RCOMND DATA SUM
 * LEN = length from COMND/RCOMND through SUM (inclusive).
 * SUM = (FLAG1_H + FLAG1_L + LEN + COMND + DATA...) & 0xFF
 */
data class GsFrame(
    val isCommand: Boolean,
    val len: Int,
    val command: Int,
    val data: ByteArray,
    val sum: Int,
    val expectedSum: Int,
    val checksumOk: Boolean,
    val raw: ByteArray,
) {
    companion object {
        private const val FLAG_CMD_H = 0xAA
        private const val FLAG_CMD_L = 0x55
        private const val FLAG_RSP_H = 0xA5
        private const val FLAG_RSP_L = 0x5A

        fun scan(bytes: ByteArray): List<GsFrame> {
            val frames = mutableListOf<GsFrame>()
            var i = 0
            while (i + 4 <= bytes.size) {
                val b0 = bytes[i].toInt() and 0xFF
                val b1 = bytes[i + 1].toInt() and 0xFF
                val isCmd = b0 == FLAG_CMD_H && b1 == FLAG_CMD_L
                val isRsp = b0 == FLAG_RSP_H && b1 == FLAG_RSP_L
                if (!isCmd && !isRsp) {
                    i++
                    continue
                }
                val len = bytes[i + 2].toInt() and 0xFF
                if (len < 2) {
                    i++
                    continue
                }
                val frameEnd = i + 3 + len // FLAG(2) + LEN(1) + payload(len)
                if (frameEnd > bytes.size) break
                val command = bytes[i + 3].toInt() and 0xFF
                val dataLen = len - 2 // exclude COMND and SUM
                val data = if (dataLen > 0) {
                    bytes.copyOfRange(i + 4, i + 4 + dataLen)
                } else {
                    ByteArray(0)
                }
                val sum = bytes[frameEnd - 1].toInt() and 0xFF
                var acc = b0 + b1 + len + command
                for (d in data) acc += d.toInt() and 0xFF
                val expected = acc and 0xFF
                frames.add(
                    GsFrame(
                        isCommand = isCmd,
                        len = len,
                        command = command,
                        data = data,
                        sum = sum,
                        expectedSum = expected,
                        checksumOk = sum == expected,
                        raw = bytes.copyOfRange(i, frameEnd),
                    ),
                )
                i = frameEnd
            }
            return frames
        }
    }
}
