package io.horizontalsystems.stellarkit.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL
import io.horizontalsystems.sqlcipher.room.DatabaseKeyMismatchException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationConflictException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationInProgressException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationRequiredException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.sqlcipher.room.InsufficientDatabaseMigrationSpaceException
import io.horizontalsystems.stellarkit.PlatformContext

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
            override fun migrate(connection: SQLiteConnection) {
                connection.execSQL(
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

        /**
         * Opens the SQLCipher database [name]: a name inside the platform database directory or an absolute
         * path. [databaseKey] must be exactly 32 bytes and the file name (basename) of [name] must not be blank,
         * start with a reserved `.stellar-kit-sqlcipher`, `.bitcoin-kit-sqlcipher` or `.tron-kit-sqlcipher` prefix,
         * or contain a reserved `.sqlcipher-migrating` or `.plaintext-backup` suffix, otherwise
         * [IllegalArgumentException] is thrown before any I/O.
         *
         * Call [migrateDatabase] with the same name and key first. Failures:
         * - [DatabaseMigrationRequiredException] or [DatabaseMigrationInProgressException]: call [migrateDatabase];
         * - [DatabaseKeyMismatchException]: the file is kept; only deleting it and using a new key (data lost) recovers.
         */
        fun getInstance(context: PlatformContext, name: String, databaseKey: ByteArray): KitDatabase {
            requireValidDatabaseArguments(name, databaseKey)
            return kitDatabaseBuilder(context, name, databaseKey)
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = false)
                .build()
        }

        /**
         * Encrypts an existing plaintext database [name] with [databaseKey], keeping its data, and recovers an
         * interrupted migration. Idempotent: an already encrypted database is only verified with the key.
         * Accepts the same arguments as [getInstance], checked the same way before any I/O, and must finish before it.
         * Failures:
         * - [DatabaseKeyMismatchException]: the encrypted file is kept unchanged;
         * - [DatabaseMigrationConflictException]: another migration or clear is running; retry later;
         * - [InsufficientDatabaseMigrationSpaceException]: the plaintext database is kept unchanged.
         */
        suspend fun migrateDatabase(
            context: PlatformContext,
            name: String,
            databaseKey: ByteArray,
        ): DatabaseMigrationResult {
            requireValidDatabaseArguments(name, databaseKey)
            return migrateDatabaseFile(databaseFile(context, name), databaseKey)
        }
    }
}
