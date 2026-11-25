package io.horizontalsystems.stellarkit

import io.horizontalsystems.stellarkit.room.RawTransactionBroadcastDao
import io.horizontalsystems.stellarkit.room.RawTransactionBroadcastRecord

internal class InMemoryRawTransactionBroadcastDao : RawTransactionBroadcastDao {
    val records = mutableListOf<RawTransactionBroadcastRecord>()
    val updatedRecords = mutableListOf<RawTransactionBroadcastRecord>()
    val deletedTxHashes = mutableListOf<String>()

    override suspend fun insert(record: RawTransactionBroadcastRecord) {
        if (records.none { it.txHash == record.txHash }) {
            records.add(record)
        }
    }

    override suspend fun update(record: RawTransactionBroadcastRecord) {
        updatedRecords.add(record)
        records.removeAll { it.txHash == record.txHash }
        records.add(record)
    }

    override suspend fun dueRecords(nowSeconds: Long): List<RawTransactionBroadcastRecord> {
        return records
            .filter { it.nextRetryAt <= nowSeconds && it.validUntil > nowSeconds }
            .sortedBy { it.createdAt }
    }

    override suspend fun delete(txHash: String) {
        deletedTxHashes.add(txHash)
        records.removeAll { it.txHash == txHash }
    }

    override suspend fun deleteExpired(nowSeconds: Long) {
        records.removeAll { it.validUntil <= nowSeconds }
    }
}
