package io.horizontalsystems.stellarkit.room

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity
class RawTransactionBroadcastRecord(
    @PrimaryKey val txHash: String,
    val raw: ByteArray,
    val sourceAccountId: String,
    val sequenceNumber: Long,
    val validUntil: Long,
    val createdAt: Long,
    val retryCount: Int,
    val nextRetryAt: Long,
) {
    fun retried(nextRetryAt: Long) = RawTransactionBroadcastRecord(
        txHash = txHash,
        raw = raw,
        sourceAccountId = sourceAccountId,
        sequenceNumber = sequenceNumber,
        validUntil = validUntil,
        createdAt = createdAt,
        retryCount = retryCount + 1,
        nextRetryAt = nextRetryAt,
    )

    override fun equals(other: Any?): Boolean {
        return this === other ||
            other is RawTransactionBroadcastRecord &&
            txHash == other.txHash &&
            raw.contentEquals(other.raw) &&
            sourceAccountId == other.sourceAccountId &&
            sequenceNumber == other.sequenceNumber &&
            validUntil == other.validUntil &&
            createdAt == other.createdAt &&
            retryCount == other.retryCount &&
            nextRetryAt == other.nextRetryAt
    }

    override fun hashCode(): Int {
        var result = txHash.hashCode()
        result = 31 * result + raw.contentHashCode()
        result = 31 * result + sourceAccountId.hashCode()
        result = 31 * result + sequenceNumber.hashCode()
        result = 31 * result + validUntil.hashCode()
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + retryCount
        result = 31 * result + nextRetryAt.hashCode()
        return result
    }
}
