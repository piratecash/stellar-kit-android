package io.horizontalsystems.stellarkit.room

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import io.horizontalsystems.sqlcipher.SqlCipherDriver
import io.horizontalsystems.sqlcipher.SqlCipherMigration
import io.horizontalsystems.sqlcipher.room.DatabaseKeyMismatchException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationRequiredException
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.stellarkit.Network
import io.horizontalsystems.stellarkit.PlatformContext
import io.horizontalsystems.stellarkit.StellarKit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** How the kit wires sqlcipher-room; engine scenarios with a single key are covered by the module itself. */
class StellarDatabaseMigrationDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32) { it.toByte() }
    private val otherKey = ByteArray(32) { (it + 1).toByte() }

    private val directory: File get() = tmp.root
    private val context: PlatformContext get() = PlatformContext(directory)
    private val database: File get() = File(directory, DB_NAME)

    @Test
    fun migrateDatabase_plaintextDatabase_encryptsAndPreservesData() = runBlocking {
        createPlaintextDatabase(database, "stellar")

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(hasPlaintextSqliteHeader(database))
        assertEquals("stellar", readEncryptedValue(database, key))
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_alreadyEncrypted_reportsCountAndKeepsFileBytes() = runBlocking {
        createPlaintextDatabase(database, "stellar")
        migrate(key)
        val encryptedBytes = database.readBytes()

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(0, 1), result)
        assertArrayEquals(encryptedBytes, database.readBytes())
    }

    @Test
    fun getInstance_databaseEncryptedWithOtherKey_throwsKeyMismatchWithoutChangingFile() {
        createEncryptedDatabase(database, "stellar", key)
        val encryptedBytes = database.readBytes()

        assertThrows(DatabaseKeyMismatchException::class.java) {
            KitDatabase.getInstance(context, DB_NAME, otherKey)
        }

        assertArrayEquals(encryptedBytes, database.readBytes())
        assertEquals("stellar", readEncryptedValue(database, key))
    }

    @Test
    fun getInstance_plaintextWithoutMigration_throwsMigrationRequiredWithoutChangingFile() {
        createPlaintextDatabase(database, "stellar")
        val plaintextBytes = database.readBytes()

        assertThrows(DatabaseMigrationRequiredException::class.java) {
            KitDatabase.getInstance(context, DB_NAME, key)
        }

        assertArrayEquals(plaintextBytes, database.readBytes())
    }

    @Test
    fun migrateDatabase_stagedMigrationWasInterrupted_recoversAndMigrates() = runBlocking {
        createPlaintextDatabase(database, "stellar")
        interruptMigration(ManifestPhase.STAGED, key)

        val result = migrate(key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertEquals("stellar", readEncryptedValue(database, key))
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_stagedUnderOtherKeyWasInterrupted_restoresPlaintextAndEncryptsWithNewKey() = runBlocking {
        createPlaintextDatabase(database, "stellar")
        interruptMigration(ManifestPhase.STAGED, key)

        val result = migrate(otherKey)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertEquals("stellar", readEncryptedValue(database, otherKey))
        assertNoMigrationArtifacts()
    }

    @Test
    fun migrateDatabase_committedUnderOtherKeyWasInterrupted_throwsKeyMismatchAndKeepsCiphertext() {
        createPlaintextDatabase(database, "stellar")
        interruptMigration(ManifestPhase.COMMITTED, key)

        assertThrows(DatabaseKeyMismatchException::class.java) {
            runBlocking { migrate(otherKey) }
        }

        assertEquals("stellar", readEncryptedValue(database, key))
        assertNoMigrationArtifacts()
    }

    @Test
    fun clear_interruptedMigration_removesDatabaseFamilyAndMigrationLeftovers() {
        val walletDatabase = File(directory, WALLET_DB_NAME)
        createPlaintextDatabase(walletDatabase, "stellar")
        val entry = stagePlaintextDatabase(walletDatabase, key)
        installStagedDatabase(entry)
        listOf("-wal", "-shm", "-journal").forEach { suffix -> File("${walletDatabase.path}$suffix").writeText("x") }
        // Written under the id the kit itself uses, so only a clear with the same id removes it.
        writeManifest(ManifestPhase.STAGED, entry, manifestFileFor(walletDatabase.path))

        StellarKit.clear(context, Network.MainNet, WALLET_ID)

        assertEquals(listOf(LOCK_FILE_NAME), directory.list()?.toList())
    }

    @Test
    fun migrateDatabase_absolutePath_isOpenedByGetInstance() = runBlocking {
        val file = StellarV2Fixture.copyTo(File(tmp.newFolder("elsewhere"), "absolute.db"))
        val otherContext = PlatformContext(tmp.newFolder("data"))

        val result = KitDatabase.migrateDatabase(otherContext, file.absolutePath, key)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(hasPlaintextSqliteHeader(file))
        val reopened = KitDatabase.getInstance(otherContext, file.absolutePath, key)
        try {
            StellarV2Fixture.assertContents(reopened)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun migrateDatabase_invalidArguments_throwBeforeAnyFileIsCreated() {
        invalidArguments().forEach { (name, databaseKey) ->
            assertThrows(IllegalArgumentException::class.java) {
                runBlocking { KitDatabase.migrateDatabase(PlatformContext(File(directory, "data")), name, databaseKey) }
            }
            assertDirectoryEmpty(name)
        }
    }

    @Test
    fun getInstance_invalidArguments_throwBeforeAnyFileIsCreated() {
        invalidArguments().forEach { (name, databaseKey) ->
            assertThrows(IllegalArgumentException::class.java) {
                KitDatabase.getInstance(PlatformContext(File(directory, "data")), name, databaseKey)
            }
            assertDirectoryEmpty(name)
        }
    }

    @Test
    fun migrateAndClear_bitcoinAndTronKitFilesInSameDirectory_areIgnoredAndUntouched() = runBlocking {
        val foreignFiles = listOf("bitcoin-kit", "tron-kit").flatMap { namespace ->
            val foreignDatabase = File(directory, "$namespace.db")
            createPlaintextDatabase(backupOf(foreignDatabase), namespace)
            val manifest = File(directory, ".$namespace-sqlcipher-0123456789abcdef.json")
            manifest.writeText(manifestJson(ManifestPhase.STAGED, StagedEntry(foreignDatabase.path, "${foreignDatabase.path}$STAGING_SUFFIX")))
            val lock = File(directory, ".$namespace-sqlcipher.lock").apply { writeText("") }
            listOf(backupOf(foreignDatabase), manifest, lock)
        }
        val snapshot = foreignFiles.associateWith(File::readBytes)
        val walletDatabase = File(directory, WALLET_DB_NAME)
        createPlaintextDatabase(walletDatabase, "stellar")

        val result = StellarKit.migrateDatabase(context, Network.MainNet, WALLET_ID, key)
        KitDatabase.getInstance(context, walletDatabase.name, key).close()
        StellarKit.clear(context, Network.MainNet, WALLET_ID)

        assertEquals(DatabaseMigrationResult(1, 0), result)
        assertFalse(walletDatabase.exists())
        snapshot.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
        assertEquals((foreignFiles.map(File::getName) + LOCK_FILE_NAME).sorted(), directory.list()?.sorted())
    }

    private suspend fun migrate(databaseKey: ByteArray): DatabaseMigrationResult =
        KitDatabase.migrateDatabase(context, DB_NAME, databaseKey)

    private fun invalidArguments(): List<Pair<String, ByteArray>> = listOf(
        DB_NAME to ByteArray(31),
        "" to key,
        " " to key,
        "${directory.path}/ " to key,
        ".stellar-kit-sqlcipher-wallet.json" to key,
        ".stellar-kit-sqlcipher.lock" to key,
        ".bitcoin-kit-sqlcipher-x.json" to key,
        ".tron-kit-sqlcipher-x.json" to key,
        "stellar-a-MainNet$BACKUP_SUFFIX" to key,
        "stellar-a-MainNet-wal$BACKUP_SUFFIX" to key,
        "stellar-a-MainNet$STAGING_SUFFIX" to key,
        "stellar-a-MainNet$STAGING_SUFFIX-wal" to key,
    )

    private fun assertDirectoryEmpty(name: String) {
        assertEquals("files after '$name'", emptyList<String>(), directory.list()?.toList())
    }

    private fun createPlaintextDatabase(file: File, value: String) {
        BundledSQLiteDriver().open(file.path).use { connection -> createSample(connection::execSQL, value) }
    }

    private fun createEncryptedDatabase(file: File, value: String, databaseKey: ByteArray) {
        SqlCipherDriver(databaseKey).use { driver ->
            driver.open(file.path).use { connection -> createSample(connection::execSQL, value) }
        }
    }

    private fun createSample(execSql: (String) -> Unit, value: String) {
        execSql("CREATE TABLE sample(value TEXT NOT NULL)")
        execSql("INSERT INTO sample VALUES('$value')")
    }

    private fun readEncryptedValue(file: File, databaseKey: ByteArray): String = SqlCipherDriver(databaseKey).use { driver ->
        driver.open(file.path).use { connection ->
            connection.prepare("SELECT value FROM sample").use { statement ->
                check(statement.step()) { "Test database contains no sample row" }
                statement.getText(0)
            }
        }
    }

    // Leaves the files a migration killed in [phase] leaves behind: ciphertext under [databaseKey] and its manifest.
    private fun interruptMigration(phase: ManifestPhase, databaseKey: ByteArray) {
        val entry = stagePlaintextDatabase(database, databaseKey)
        installStagedDatabase(entry)
        writeManifest(phase, entry, File(directory, ".stellar-kit-sqlcipher-test.json"))
    }

    private fun stagePlaintextDatabase(file: File, databaseKey: ByteArray): StagedEntry {
        val staging = File("${file.path}$STAGING_SUFFIX")
        SqlCipherMigration.exportPlaintext(file.path, staging.path, databaseKey)
        return StagedEntry(file.path, staging.path)
    }

    private fun installStagedDatabase(entry: StagedEntry) {
        val databaseFile = File(entry.databasePath)
        Files.move(databaseFile.toPath(), backupOf(databaseFile).toPath(), StandardCopyOption.ATOMIC_MOVE)
        Files.move(File(entry.stagingPath).toPath(), databaseFile.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun writeManifest(phase: ManifestPhase, entry: StagedEntry, file: File) {
        file.writeText(manifestJson(phase, entry))
    }

    // The sqlcipher-room manifest format, version 1.
    private fun manifestJson(phase: ManifestPhase, entry: StagedEntry): String =
        """{"version":1,"phase":"${phase.name}","entries":[{"databasePath":${jsonString(entry.databasePath)},"stagingPath":${jsonString(entry.stagingPath)}}]}"""

    private fun jsonString(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    // Same derivation as sqlcipher-room's manifest file name: the first 8 bytes of SHA-256(migrationId) in hex.
    private fun manifestFileFor(migrationId: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(migrationId.encodeToByteArray())
        val id = digest.take(8).joinToString("") { "%02x".format(it) }
        return File(directory, ".stellar-kit-sqlcipher-$id.json")
    }

    private fun backupOf(file: File): File = File("${file.path}$BACKUP_SUFFIX")

    private fun assertNoMigrationArtifacts() {
        val artifacts = directory.list()?.filter { name ->
            name.endsWith(".json") || name.endsWith(STAGING_SUFFIX) || name.endsWith(BACKUP_SUFFIX)
        }
        assertTrue("migration artifacts left: $artifacts", artifacts.isNullOrEmpty())
    }

    private data class StagedEntry(val databasePath: String, val stagingPath: String)

    private enum class ManifestPhase { STAGED, COMMITTED }

    private companion object {
        const val DB_NAME = "stellar-migration-MainNet"
        const val WALLET_ID = "wallet"
        const val WALLET_DB_NAME = "stellar-wallet-MainNet"
        const val LOCK_FILE_NAME = ".stellar-kit-sqlcipher.lock"
        const val STAGING_SUFFIX = ".sqlcipher-migrating"
        const val BACKUP_SUFFIX = ".plaintext-backup"
    }
}
