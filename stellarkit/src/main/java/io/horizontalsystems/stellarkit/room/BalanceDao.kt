package io.horizontalsystems.stellarkit.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface BalanceDao {
    @Transaction
    suspend fun deleteAllAndInsertNew(balances: List<AssetBalance>) {
        delete()
        insertAll(balances)
    }

    @Query("DELETE FROM AssetBalance")
    suspend fun delete()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(balances: List<AssetBalance>)

    @Query("SELECT * FROM AssetBalance")
    fun getAssetBalancesFlow(): Flow<List<AssetBalance>>

    @Query("SELECT * FROM AssetBalance WHERE asset = :asset")
    suspend fun getBalance(asset: StellarAsset): AssetBalance?

    @Query("SELECT * FROM AssetBalance")
    suspend fun getAll(): List<AssetBalance>
}
