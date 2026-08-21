package com.chyi.alog.decode

import java.io.File

object DecodeCommand {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("usage: alog-decode <file.alog>")
            System.exit(2)
        }
        for (path in args) {
            AlogDecoder.decode(File(path), System.err).forEach { println(it) }
        }
    }

    fun decodeFile(file: File): List<String> {
        val lines = AlogDecoder.decode(file, System.err)
        lines.forEach { println(it) }
        return lines
    }
}
