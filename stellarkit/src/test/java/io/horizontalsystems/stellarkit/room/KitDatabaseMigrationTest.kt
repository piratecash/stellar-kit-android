package io.horizontalsystems.stellarkit.room

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KitDatabaseMigrationTest {
    @Test
    fun migration1To2_createsRawTransactionBroadcastRecordTable() {
        val helper = openDatabase()
        val database = helper.writableDatabase

        KitDatabase.MIGRATION_1_2.migrate(database)

        val columns = tableColumns(database, "RawTransactionBroadcastRecord")

        assertEquals("TEXT", columns["txHash"])
        assertEquals("BLOB", columns["raw"])
        assertEquals("TEXT", columns["sourceAccountId"])
        assertEquals("INTEGER", columns["sequenceNumber"])
        assertEquals("INTEGER", columns["validUntil"])
        assertEquals("INTEGER", columns["createdAt"])
        assertEquals("INTEGER", columns["retryCount"])
        assertEquals("INTEGER", columns["nextRetryAt"])
        assertTrue(columns.containsKey("txHash"))

        database.close()
        helper.close()
    }

    @Test
    fun migration1To2_preservesExistingData() {
        val helper = openDatabase()
        val database = helper.writableDatabase
        database.execSQL("CREATE TABLE `ExistingData` (`id` INTEGER NOT NULL PRIMARY KEY, `value` TEXT NOT NULL)")
        database.execSQL("INSERT INTO `ExistingData` (`id`, `value`) VALUES (1, 'kept')")

        KitDatabase.MIGRATION_1_2.migrate(database)

        database.query("SELECT `value` FROM `ExistingData` WHERE `id` = 1").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("kept", cursor.getString(0))
        }

        database.close()
        helper.close()
    }

    private fun openDatabase(): SupportSQLiteOpenHelper {
        return FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(
                ApplicationProvider.getApplicationContext()
            )
                .name(null)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit
                        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    }
                )
                .build()
        )
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
}
