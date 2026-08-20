package com.chyi.alog.crypto

import java.security.PublicKey

/**
 * 文件加密配置，由 [com.chyi.alog.printer.file.FilePrinter.Builder] 在开启加密时组装。
 *
 * @property enabled 是否加密落盘
 * @property keyId 密钥标识，写入文件头供解码端选择私钥
 * @property publicKey RSA 公钥，用于封装每文件 DEK；未加密时为 `null`
 */
data class CryptoConfig(
    val enabled: Boolean,
    val keyId: String,
    val publicKey: PublicKey?,
)
