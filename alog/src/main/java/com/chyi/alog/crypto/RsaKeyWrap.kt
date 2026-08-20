package com.chyi.alog.crypto

import java.security.KeyFactory
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher

/**
 * 用 RSA/ECB/PKCS1Padding 封装（wrap）文件 DEK。
 *
 * 私钥只应出现在解码端（`:alog-decode`），不要打进写日志的业务 App。
 */
object RsaKeyWrap {
    private const val TRANSFORMATION = "RSA/ECB/PKCS1Padding"

    /** 解析 X.509 PEM 公钥。 */
    fun parsePublicPem(pem: String): PublicKey {
        val spec = X509EncodedKeySpec(decodePem(pem, "PUBLIC KEY"))
        return KeyFactory.getInstance("RSA").generatePublic(spec)
    }

    /** 解析 PKCS#8 PEM 私钥。仅解码端使用。 */
    fun parsePrivatePem(pem: String): PrivateKey {
        val spec = PKCS8EncodedKeySpec(decodePem(pem, "PRIVATE KEY"))
        return KeyFactory.getInstance("RSA").generatePrivate(spec)
    }

    /** 用公钥封装 AES DEK。 */
    fun wrap(publicKey: PublicKey, dek: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(dek)
    }

    /** 用私钥解封 AES DEK。仅解码端使用。 */
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
