package com.chyi.alog.crypto

import com.chyi.alog.store.BlockCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Test
import java.security.KeyPairGenerator

class CryptoTest {
    @Test
    fun aesRoundTrip() {
        val key = AesGcm.randomKey()
        val nonce = AesGcm.randomNonce()
        val plain = BlockCodec.deflate("secret-line\n".toByteArray())
        val enc = AesGcm.encrypt(key, nonce, plain)
        assertArrayEquals(plain, AesGcm.decrypt(key, nonce, enc))
    }

    @Test
    fun rsaWrapsDek() {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(2048)
        val pair = kpg.generateKeyPair()
        val dek = AesGcm.randomKey()
        val wrapped = RsaKeyWrap.wrap(pair.public, dek)
        val unwrapped = RsaKeyWrap.unwrap(pair.private, wrapped)
        assertEquals(dek.toList(), unwrapped.toList())
    }
}
