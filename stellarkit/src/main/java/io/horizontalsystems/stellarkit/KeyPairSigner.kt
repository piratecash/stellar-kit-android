package io.horizontalsystems.stellarkit

import org.stellar.sdk.KeyPair
import org.stellar.sdk.xdr.DecoratedSignature
import org.stellar.sdk.xdr.Signature

class KeyPairSigner(private val keyPair: KeyPair) : Signer {
    override val publicKey: ByteArray get() = keyPair.publicKey
    override fun canSign() = keyPair.canSign()
    override suspend fun sign(hash: ByteArray): DecoratedSignature {
        val signatureBytes = keyPair.sign(hash)

        val signature = Signature()
        signature.signature = signatureBytes

        val decoratedSignature = DecoratedSignature()
        decoratedSignature.hint = keyPair.getSignatureHint()
        decoratedSignature.signature = signature
        return decoratedSignature
    }
} 