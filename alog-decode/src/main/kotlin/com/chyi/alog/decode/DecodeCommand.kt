package com.chyi.alog.decode

import com.chyi.alog.crypto.AesGcm
import com.chyi.alog.crypto.RsaKeyWrap
import com.chyi.alog.store.BlockCodec
import com.chyi.alog.store.FileHeader
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
        val privateKey = keyPath?.let { RsaKeyWrap.parsePrivatePem(File(it).readText()) }
        for (path in files) {
            decodeFile(File(path), privateKey)
        }
    }

    fun decodeFile(file: File, privateKey: java.security.PrivateKey?): List<String> {
        val bytes = file.readBytes()
        val parsedHeader = FileHeader.parse(bytes)
        val start: Int
        var dek: ByteArray? = null
        if (parsedHeader != null) {
            val (header, offset) = parsedHeader
            start = offset
            if (header.flags and FileHeader.FLAG_HAS_DEK != 0) {
                if (privateKey == null) {
                    throw IllegalStateException("encrypted file requires --key private.pem")
                }
                dek = try {
                    RsaKeyWrap.unwrap(privateKey, header.wrappedDek)
                } catch (t: Throwable) {
                    throw IllegalStateException("wrong private key or corrupted wrapped DEK: ${t.message}")
                }
            }
        } else if (bytes.isNotEmpty() && bytes[0] == '{'.code.toByte()) {
            return String(bytes, Charsets.UTF_8).split('\n').filter { it.isNotBlank() }.also { lines ->
                lines.forEach { println(it) }
            }
        } else {
            start = 0
        }
        val scan = BlockCodec.scan(bytes, start)
        for (bad in scan.badOffsets) {
            System.err.println("bad block at offset=$bad")
        }
        val lines = mutableListOf<String>()
        for (block in scan.blocks) {
            try {
                var payload = block.payload
                if (block.flags and BlockCodec.FLAG_ENCRYPTED != 0) {
                    val key = dek ?: throw IllegalStateException("block encrypted but no DEK")
                    payload = AesGcm.decrypt(key, block.nonce, payload)
                }
                if (block.flags and BlockCodec.FLAG_COMPRESSED != 0) {
                    payload = BlockCodec.inflate(payload)
                }
                val text = String(payload, Charsets.UTF_8)
                text.split('\n').filter { it.isNotBlank() }.forEach {
                    println(it)
                    lines.add(it)
                }
            } catch (t: Throwable) {
                System.err.println("skip block seq=${block.seq} offset=${block.offset}: ${t.message}")
            }
        }
        return lines
    }
}
