package io.horizontalsystems.stellarkit.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        AssetBalance::class,
        Operation::class,
        OperationSyncState::class,
        Tag::class,
        RawTransactionBroadcastRecord::class,
    ],
    version = 2
)
@TypeConverters(
    ConverterBigDecimal::class,
    ConverterStellarAsset::class,
    ConverterStellarAssetAsset::class,
    ConverterListOfStrings::class,
)
abstract class KitDatabase : RoomDatabase() {
    abstract fun balanceDao(): BalanceDao
    abstract fun operationDao(): OperationDao
    internal abstract fun rawTransactionBroadcastDao(): RawTransactionBroadcastDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `RawTransactionBroadcastRecord` (
                        `txHash` TEXT NOT NULL,
                        `raw` BLOB NOT NULL,
                        `sourceAccountId` TEXT NOT NULL,
                        `sequenceNumber` INTEGER NOT NULL,
                        `validUntil` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        `retryCount` INTEGER NOT NULL,
                        `nextRetryAt` INTEGER NOT NULL,
                        PRIMARY KEY(`txHash`)
                    )
                    """.trimIndent()
                )
            }
        }

        fun getInstance(context: Context, name: String): KitDatabase {
            return Room.databaseBuilder(context, KitDatabase::class.java, name)
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade()
                .build()
        }
    }
}
