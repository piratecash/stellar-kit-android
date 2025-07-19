package io.horizontalsystems.stellarkit

interface Signer {
    val publicKey: ByteArray
    fun canSign(): Boolean
    suspend fun sign(data: ByteArray): ByteArray
} 