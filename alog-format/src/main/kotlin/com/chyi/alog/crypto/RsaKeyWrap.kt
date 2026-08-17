package com.chyi.alog.crypto

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

object RsaKeyWrap {
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    fun parsePublicPem(pem: String): PublicKey {
        val spec = X509EncodedKeySpec(decodePem(pem, "PUBLIC KEY"))
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    fun parsePrivatePem(pem: String): PrivateKey {
        val spec = PKCS8EncodedKeySpec(decodePem(pem, "PRIVATE KEY"))
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    fun wrap(publicKey: PublicKey, dek: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(dek)
    }

    fun unwrap(privateKey: PrivateKey, wrapped: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, privateKey)
        return cipher.doFinal(wrapped)
    }

    private fun decodePem(pem: String, type: String): ByteArray {
        val body = pem
            .replace("-----BEGIN $type-----", "")
            .replace("-----END $type-----", "")
        return PemBase64.decode(body)
    }
}
