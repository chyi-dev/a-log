package com.chyi.alog.store

import java.io.File
import java.nio.charset.StandardCharsets

object AlogTestDecode {
    fun lineCount(dir: File): Int = linesInDir(dir).size

    fun linesInDir(dir: File): List<String> {
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".alog") }?.sortedBy { it.name }.orEmpty()
        return files.flatMap { linesInFile(it) }
    }

    fun linesInFile(file: File): List<String> {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return emptyList()
        if (bytes[0] == '{'.code.toByte()) {
            return String(bytes, StandardCharsets.UTF_8).split('\n').filter { it.isNotBlank() }
        }
        val parsed = FileHeader.parse(bytes)
        val start = parsed?.second ?: 0
        val scan = BlockCodec.scan(bytes, start)
        val out = mutableListOf<String>()
        for (block in scan.blocks) {
            var payload = block.payload
            if (block.flags and BlockCodec.FLAG_COMPRESSED != 0) {
                payload = BlockCodec.inflate(payload)
            }
            String(payload, StandardCharsets.UTF_8).split('\n').filter { it.isNotBlank() }.forEach { out.add(it) }
        }
        return out
    }
}
