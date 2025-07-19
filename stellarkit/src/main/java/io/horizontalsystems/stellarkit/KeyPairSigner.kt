package io.horizontalsystems.stellarkit

import org.stellar.sdk.KeyPair

class KeyPairSigner(private val keyPair: KeyPair) : Signer {
    override val publicKey: ByteArray get() = keyPair.publicKey
    override fun canSign() = keyPair.canSign()
    override suspend fun sign(data: ByteArray): ByteArray = keyPair.sign(data)
} 