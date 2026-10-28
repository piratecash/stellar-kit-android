package io.horizontalsystems.stellarkit

import org.stellar.sdk.Transaction
import org.stellar.sdk.xdr.DecoratedSignature

interface Signer {
    val publicKey: ByteArray
    fun canSign(): Boolean
    suspend fun sign(hash: ByteArray): DecoratedSignature
    suspend fun signTransaction(transaction: Transaction): DecoratedSignature? = null
} 