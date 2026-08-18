package com.chyi.alog.decode

import com.chyi.alog.crypto.AesGcm
import com.chyi.alog.crypto.RsaKeyWrap
import com.chyi.alog.store.BlockCodec
import com.chyi.alog.store.FileHeader
import java.io.File
import java.io.PrintStream
import java.security.PrivateKey

object AlogDecoder {
    @JvmStatic
    @JvmOverloads
    fun decode(file: File, privateKeyPem: String?, err: PrintStream? = null): List<String> {
        val key = privateKeyPem?.let { RsaKeyWrap.parsePrivatePem(it) }
        return decode(file.readBytes(), key, err)
    }

    @JvmStatic
    @JvmOverloads
    fun decode(bytes: ByteArray, privateKey: PrivateKey?, err: PrintStream? = null): List<String> {
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
                if (block.flags and BlockCodec.FLAG_ENCRYPTED != 0) {
                    val key = dek ?: throw IllegalStateException("block encrypted but no DEK")
                    payload = AesGcm.decrypt(key, block.nonce, payload)
                }
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
