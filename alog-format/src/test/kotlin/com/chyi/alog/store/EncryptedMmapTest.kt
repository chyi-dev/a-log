package com.chyi.alog.store

import com.chyi.alog.crypto.AesGcm
import com.chyi.alog.crypto.CryptoConfig
import com.chyi.alog.crypto.RsaKeyWrap
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.security.KeyPairGenerator

class EncryptedMmapTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun encryptedFileNeedsPrivateKey() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val dir = tmp.newFolder("enc")
        val writer = MmapLogWriter(
            dir = dir,
            namePrefix = "alog",
            crypto = CryptoConfig(true, "dev-1", pair.public),
        )
        writer.append("{\"msg\":\"secret-payload\"}")
        writer.flush(true)
        writer.close()
        val alog = dir.listFiles { f -> f.name.endsWith(".alog") }!!.first()
        val bytes = alog.readBytes()
        val (header, start) = FileHeader.parse(bytes)!!
        assertTrue(header.wrappedDek.isNotEmpty())
        val dek = RsaKeyWrap.unwrap(pair.private, header.wrappedDek)
        val scan = BlockCodec.scan(bytes, start)
        val texts = scan.blocks.map {
            val plain = AesGcm.decrypt(dek, it.nonce, it.payload)
            String(BlockCodec.inflate(plain), Charsets.UTF_8)
        }
        assertTrue(texts.joinToString("").contains("secret-payload"))
    }
}
