package com.chyi.alog.crypto

import com.chyi.alog.ALogDefaults
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AesGcm {
    private const val GCM_TAG_BITS = 128

    fun randomKey(): ByteArray {
        val key = ByteArray(ALogDefaults.AES_KEY_BYTES)
        SecureRandom().nextBytes(key)
        return key
    }

    fun randomNonce(): ByteArray {
        val nonce = ByteArray(ALogDefaults.GCM_NONCE_BYTES)
        SecureRandom().nextBytes(nonce)
        return nonce
    }

    fun encrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        return cipher.doFinal(plain)
    }

    fun decrypt(key: ByteArray, nonce: ByteArray, cipherBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key, "AES"),
            GCMParameterSpec(GCM_TAG_BITS, nonce),
        )
        return cipher.doFinal(cipherBytes)
    }
}
