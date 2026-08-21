package com.chyi.alog.decode

import com.chyi.alog.store.BlockCodec
import com.chyi.alog.store.FileHeader
import java.io.File
import java.io.PrintStream

object AlogDecoder {
    @JvmStatic
    @JvmOverloads
    fun decode(file: File, err: PrintStream? = null): List<String> {
        return decode(file.readBytes(), err)
    }

    @JvmStatic
    @JvmOverloads
    fun decode(bytes: ByteArray, err: PrintStream? = null): List<String> {
        val parsedHeader = FileHeader.parse(bytes)
        val start: Int
        if (parsedHeader != null) {
            start = parsedHeader.second
        } else if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
            return String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotBlank() }
        } else {
            start = 0
        }
        val scan = BlockCodec.scan(bytes, start)
        for (bad in scan.badOffsets) {
            err?.println("bad block at offset=$bad")
        }
        val lines = mutableListOf<String>()
        for (block in scan.blocks) {
            try {
                var payload = block.payload
                if (block.flags and BlockCodec.FLAG_COMPRESSED != 0) {
                    payload = BlockCodec.inflate(payload)
                }
                val text = String(payload, Charsets.UTF_8)
                text.split('\n').filter { it.isNotBlank() }.forEach { lines.add(it) }
            } catch (t: Throwable) {
                err?.println("skip block seq=${block.seq} offset=${block.offset}: ${t.message}")
            }
        }
        return lines
    }
}
