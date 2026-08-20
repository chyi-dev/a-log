package com.chyi.alog.crypto

import com.chyi.alog.ALogDefaults
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AES-GCM 块加解密，供 mmap 落盘与解码端使用。一般无需在业务代码中直接调用。
 */
object AesGcm {
    private const val GCM_TAG_BITS = 128

    /** 生成 [ALogDefaults.AES_KEY_BYTES] 字节随机密钥。 */
    fun randomKey(): ByteArray {
        val key = ByteArray(ALogDefaults.AES_KEY_BYTES)
        SecureRandom().nextBytes(key)
        return key
    }

    /** 生成 [ALogDefaults.GCM_NONCE_BYTES] 字节随机 nonce。 */
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
