package io.horizontalsystems.stellarkit

import org.stellar.sdk.AbstractTransaction
import org.stellar.sdk.Transaction
import org.stellar.sdk.TransactionPreconditions
import org.stellar.sdk.xdr.TransactionEnvelope
import java.math.BigInteger
import java.util.Base64
import org.stellar.sdk.Network as StellarSdkNetwork

internal data class DecodedRawStellarTransaction(
    val raw: ByteArray,
    val envelopeXdrBase64: String,
    val txHash: String,
    val sourceAccountId: String,
    val sequenceNumber: Long,
    val validUntil: Long?,
)

internal object RawTransactionUtils {
    fun decode(
        raw: ByteArray,
        network: StellarSdkNetwork,
    ): DecodedRawStellarTransaction {
        if (raw.isEmpty()) {
            throw TransactionError.InvalidRawTransaction("Raw transaction is empty")
        }

        val envelope = parseEnvelope(raw)
        val transaction = parseTransaction(envelope, network)
        if (transaction.signatures.isEmpty()) {
            throw TransactionError.InvalidRawTransaction("Raw transaction is not signed")
        }

        return DecodedRawStellarTransaction(
            raw = raw,
            envelopeXdrBase64 = Base64.getEncoder().encodeToString(raw),
            txHash = transaction.hashHex(),
            sourceAccountId = transaction.sourceAccount,
            sequenceNumber = transaction.sequenceNumber,
            validUntil = transaction.validUntil,
        )
    }

    fun rawBytes(transaction: AbstractTransaction): ByteArray =
        Base64.getDecoder().decode(transaction.toEnvelopeXdrBase64())

    val Transaction.validUntil: Long?
        get() {
            val maxTime = timeBounds?.maxTime ?: return null
            if (maxTime == TransactionPreconditions.TIMEOUT_INFINITE) return null
            return maxTime.toLongExact()
        }

    private fun parseEnvelope(raw: ByteArray): TransactionEnvelope {
        return try {
            TransactionEnvelope.fromXdrByteArray(raw)
        } catch (error: Throwable) {
            throw TransactionError.InvalidRawTransaction("Raw transaction XDR is invalid")
        }
    }

    private fun parseTransaction(
        envelope: TransactionEnvelope,
        network: StellarSdkNetwork,
    ): Transaction {
        val transaction = try {
            AbstractTransaction.fromEnvelopeXdr(envelope, network)
        } catch (error: Throwable) {
            throw TransactionError.InvalidRawTransaction("Raw transaction envelope is invalid")
        }

        return transaction as? Transaction
            ?: throw TransactionError.InvalidRawTransaction("Only classic Stellar transactions are supported")
    }

    private fun BigInteger.toLongExact(): Long =
        try {
            longValueExact()
        } catch (error: ArithmeticException) {
            throw TransactionError.InvalidRawTransaction("Transaction timebound exceeds Long range")
        }
}
