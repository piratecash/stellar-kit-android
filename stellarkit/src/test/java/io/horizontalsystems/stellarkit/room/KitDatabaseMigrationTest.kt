package io.horizontalsystems.stellarkit.room

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

// MIGRATION_1_2 only creates RawTransactionBroadcastRecord; Room validates the migrated table against this entity.
@Database(version = 2, entities = [RawTransactionBroadcastRecord::class], exportSchema = false)
internal abstract class Migration1To2TestDatabase : RoomDatabase()

@RunWith(RobolectricTestRunner::class)
class KitDatabaseMigrationTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun tearDown() {
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun migration1To2_createsRawTransactionBroadcastRecordTable() {
        seedV1Database()

        val room = openMigratedDatabase()
        val columns = tableColumns(room.openHelper.writableDatabase, "RawTransactionBroadcastRecord")

        assertEquals("TEXT", columns["txHash"])
        assertEquals("BLOB", columns["raw"])
        assertEquals("TEXT", columns["sourceAccountId"])
        assertEquals("INTEGER", columns["sequenceNumber"])
        assertEquals("INTEGER", columns["validUntil"])
        assertEquals("INTEGER", columns["createdAt"])
        assertEquals("INTEGER", columns["retryCount"])
        assertEquals("INTEGER", columns["nextRetryAt"])
        assertTrue(columns.containsKey("txHash"))

        room.close()
    }

    @Test
    fun migration1To2_preservesExistingData() {
        seedV1Database { database ->
            database.execSQL("CREATE TABLE `ExistingData` (`id` INTEGER NOT NULL PRIMARY KEY, `value` TEXT NOT NULL)")
            database.execSQL("INSERT INTO `ExistingData` (`id`, `value`) VALUES (1, 'kept')")
        }

        val room = openMigratedDatabase()

        room.openHelper.writableDatabase.query("SELECT `value` FROM `ExistingData` WHERE `id` = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("kept", cursor.getString(0))
        }

        room.close()
    }

    // Room invokes MIGRATION_1_2 itself while opening the v1 file, the same path production code uses.
    private fun openMigratedDatabase(): Migration1To2TestDatabase =
        Room.databaseBuilder(context, Migration1To2TestDatabase::class.java, DB_NAME)
            .addMigrations(KitDatabase.MIGRATION_1_2)
            .allowMainThreadQueries()
            .build()

    private fun seedV1Database(seed: (SupportSQLiteDatabase) -> Unit = {}) {
        context.deleteDatabase(DB_NAME)
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(DB_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) = seed(db)
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    }
                )
                .build()
        )
        helper.writableDatabase.close()
        helper.close()
    }

    private fun tableColumns(database: SupportSQLiteDatabase, table: String): Map<String, String> {
        val columns = mutableMapOf<String, String>()
        database.query("PRAGMA table_info(`$table`)").use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            val typeIndex = cursor.getColumnIndex("type")
            while (cursor.moveToNext()) {
                columns[cursor.getString(nameIndex)] = cursor.getString(typeIndex)
            }
        }
        return columns
    }

    private companion object {
        const val DB_NAME = "migration-1-2-test"
    }
}
