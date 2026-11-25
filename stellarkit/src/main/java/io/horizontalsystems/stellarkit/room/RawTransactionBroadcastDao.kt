package io.horizontalsystems.stellarkit.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
internal interface RawTransactionBroadcastDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(record: RawTransactionBroadcastRecord)

    @Update
    suspend fun update(record: RawTransactionBroadcastRecord)

    @Query(
        """
        SELECT * FROM RawTransactionBroadcastRecord
        WHERE nextRetryAt <= :nowSeconds AND validUntil > :nowSeconds
        ORDER BY createdAt ASC
        """
    )
    suspend fun dueRecords(nowSeconds: Long): List<RawTransactionBroadcastRecord>

    @Query("DELETE FROM RawTransactionBroadcastRecord WHERE txHash = :txHash")
    suspend fun delete(txHash: String)

    @Query("DELETE FROM RawTransactionBroadcastRecord WHERE validUntil <= :nowSeconds")
    suspend fun deleteExpired(nowSeconds: Long)
}
