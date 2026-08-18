package com.chyi.alog.decode

import java.io.File

object DecodeCommand {
    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            System.err.println("usage: alog-decode [--key private.pem] <file.alog>")
            System.exit(2)
        }
        var keyPath: String? = null
        val files = mutableListOf<String>()
        var i = 0
        while (i < args.size) {
            if (args[i] == "--key") {
                keyPath = args.getOrNull(i + 1)
                i += 2
            } else {
                files.add(args[i])
                i++
            }
        }
        if (files.isEmpty()) {
            System.err.println("missing file")
            System.exit(2)
        }
        val pem = keyPath?.let { File(it).readText() }
        for (path in files) {
            AlogDecoder.decode(File(path), pem, System.err).forEach { println(it) }
        }
    }

    fun decodeFile(file: File, privateKey: java.security.PrivateKey?): List<String> {
        val lines = AlogDecoder.decode(file.readBytes(), privateKey, System.err)
        lines.forEach { println(it) }
        return lines
    }
}
