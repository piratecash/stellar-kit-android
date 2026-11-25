package io.horizontalsystems.stellarkit.room

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.horizontalsystems.sqlcipher.room.DatabaseKeyMismatchException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationRequiredException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.stellarkit.Network
import io.horizontalsystems.stellarkit.StellarKit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/** The SQLCipher path on a real Android runtime: native library, SupportOpenHelperFactory and getDatabasePath. */
@RunWith(AndroidJUnit4::class)
class EncryptedDatabaseAndroidTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val key = ByteArray(32) { (it * 3).toByte() }
    private val otherKey = ByteArray(32) { (it * 5 + 1).toByte() }
    private val walletId = "encrypted-${UUID.randomUUID()}"
    private val databaseName = "stellar-$walletId-${Network.MainNet.name}"
    private val databaseFile = context.getDatabasePath(databaseName)

    @After
    fun tearDown() {
        StellarKit.clear(context, Network.MainNet, walletId)
    }

    @Test
    fun migrateDatabase_room261Version2Fixture_preservesStoredWalletState() = runBlocking {
        StellarV2Fixture.copyTo(databaseFile)

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(hasPlaintextSqliteHeader(databaseFile))
        assertFixtureOpensWith(key)
    }

    @Test
    fun migrateDatabase_alreadyEncrypted_reportsAlreadyEncrypted() = runBlocking {
        StellarV2Fixture.copyTo(databaseFile)
        migrate(key)

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(0, 1), result)
    }

    @Test
    fun getInstance_otherKey_throwsKeyMismatchWithoutChangingFile() = runBlocking {
        StellarV2Fixture.copyTo(databaseFile)
        migrate(key)
        val encryptedBytes = databaseFile.readBytes()

        assertThrows(DatabaseKeyMismatchException::class.java) {
            KitDatabase.getInstance(context, databaseName, otherKey)
        }

        assertArrayEquals(encryptedBytes, databaseFile.readBytes())
        assertFixtureOpensWith(key)
    }

    @Test
    fun getInstance_plaintextWithoutMigration_throwsMigrationRequired() {
        StellarV2Fixture.copyTo(databaseFile)
        val plaintextBytes = databaseFile.readBytes()

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            KitDatabase.getInstance(context, databaseName, key)
        }

        assertArrayEquals(plaintextBytes, databaseFile.readBytes())
    }

    @Test
    fun clear_encryptedDatabase_removesEveryFile() = runBlocking {
        StellarV2Fixture.copyTo(databaseFile)
        migrate(key)
        assertFixtureOpensWith(key)

        StellarKit.clear(context, Network.MainNet, walletId)

        val leftovers = databaseFile.parentFile?.list()?.filter { name ->
            name.startsWith(databaseFile.name) || (name.startsWith(".stellar-kit-sqlcipher-") && name.endsWith(".json"))
        }
        assertTrue("leftovers: $leftovers", leftovers.isNullOrEmpty())
    }

    private suspend fun migrate(databaseKey: ByteArray): DatabaseMigrationResult =
        StellarKit.migrateDatabase(context, Network.MainNet, walletId, databaseKey)

    private suspend fun assertFixtureOpensWith(databaseKey: ByteArray) {
        val database = KitDatabase.getInstance(context, databaseName, databaseKey)
        try {
            StellarV2Fixture.assertContents(database)
        } finally {
            database.close()
        }
    }
}
