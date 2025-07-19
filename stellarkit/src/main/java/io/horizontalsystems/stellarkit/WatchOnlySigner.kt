package io.horizontalsystems.stellarkit

class WatchOnlySigner(override val publicKey: ByteArray) : Signer {
    override fun canSign() = false
    override suspend fun sign(data: ByteArray): ByteArray = throw IllegalStateException("Watch-only wallet cannot sign")
} 