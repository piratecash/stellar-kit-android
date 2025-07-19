package io.horizontalsystems.stellarkit

import org.stellar.sdk.xdr.DecoratedSignature

class WatchOnlySigner(override val publicKey: ByteArray) : Signer {
    override fun canSign() = false
    override suspend fun sign(hash: ByteArray): DecoratedSignature =
        throw IllegalStateException("Watch-only wallet cannot sign")
} 