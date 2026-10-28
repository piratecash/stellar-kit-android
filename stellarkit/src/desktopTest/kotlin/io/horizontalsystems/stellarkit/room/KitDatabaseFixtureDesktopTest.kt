package io.horizontalsystems.stellarkit.room

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.horizontalsystems.sqlcipher.SqlCipherDriver
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.stellarkit.Network
import io.horizontalsystems.stellarkit.PlatformContext
import io.horizontalsystems.stellarkit.StellarKit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The Room 2.6.1 plaintext fixture survives the SQLCipher migration with every stored value intact. */
class KitDatabaseFixtureDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32) { (it * 7).toByte() }

    @Test
    fun migrateDatabase_room261Version2Fixture_preservesStoredWalletState() = runBlocking {
        val context = PlatformContext(tmp.root)
        val file = StellarV2Fixture.copyTo(File(tmp.root, DB_NAME))
        val plaintextQueue = BundledSQLiteDriver().open(file.path).use(::readQueue)

        val result = StellarKit.migrateDatabase(context, Network.MainNet, WALLET_ID, key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(hasPlaintextSqliteHeader(file))
        val database = KitDatabase.getInstance(context, DB_NAME, key)
        try {
            StellarV2Fixture.assertContents(database)
        } finally {
            database.close()
        }
        SqlCipherDriver(key).use { driver ->
            driver.open(file.path).use { connection ->
                assertEquals(2L, connection.singleLong("PRAGMA user_version"))
                assertEquals(plaintextQueue, readQueue(connection))
            }
        }
    }

    // Every stored column of the broadcast queue, raw bytes as hex, so the comparison is byte for byte.
    private fun readQueue(connection: SQLiteConnection): List<List<String>> =
        connection.prepare("SELECT * FROM RawTransactionBroadcastRecord ORDER BY txHash").use { statement ->
            buildList {
                while (statement.step()) {
                    add(List(statement.getColumnCount()) { column ->
                        if (statement.getColumnName(column) == "raw") statement.getBlob(column).toHexString()
                        else statement.getText(column)
                    })
                }
            }
        }

    private fun SQLiteConnection.singleLong(sql: String): Long = prepare(sql).use { statement ->
        check(statement.step()) { "$sql returned no row" }
        statement.getLong(0)
    }

    private companion object {
        const val WALLET_ID = "fixture"
        const val DB_NAME = "stellar-fixture-MainNet"
    }
}
