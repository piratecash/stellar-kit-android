package io.horizontalsystems.stellarkit.models

class SignedRawStellarTransaction(
    val raw: ByteArray,
    val txHash: String,
    val sourceAccountId: String,
    val sequenceNumber: Long,
    val validUntil: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SignedRawStellarTransaction) return false

        return raw.contentEquals(other.raw) &&
            txHash == other.txHash &&
            sourceAccountId == other.sourceAccountId &&
            sequenceNumber == other.sequenceNumber &&
            validUntil == other.validUntil
    }

    override fun hashCode(): Int {
        var result = raw.contentHashCode()
        result = 31 * result + txHash.hashCode()
        result = 31 * result + sourceAccountId.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + validUntil.hashCode()
        return result
    }
}

data class RawTransactionRetryMetadata(
    val sourceAccountId: String,
    val sequenceNumber: Long,
    val validUntil: Long,
)

data class RawTransactionBroadcastResult(
    val txHash: String,
    val status: RawTransactionBroadcastStatus,
)

enum class RawTransactionBroadcastStatus {
    Submitted,
    Queued,
    AlreadyKnown,
}
