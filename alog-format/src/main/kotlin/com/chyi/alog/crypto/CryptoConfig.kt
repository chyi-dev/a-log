package com.chyi.alog.crypto

import java.security.PublicKey

data class CryptoConfig(
    val enabled: Boolean,
    val keyId: String,
    val publicKey: PublicKey?,
)
