package io.horizontalsystems.stellarkit.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.math.BigDecimal

// Fixture generated once by a temporary Robolectric test through KitDatabase.getInstance
// (Room 2.6.1, version = 2), then deleted; see stellar-v2-room-2.6.1.db.
internal object StellarV2Fixture {

    fun copyTo(target: File): File {
        target.parentFile?.mkdirs()
        val classLoader = requireNotNull(javaClass.classLoader) { "No class loader for test class" }
        val resource = requireNotNull(classLoader.getResourceAsStream("databases/stellar-v2-room-2.6.1.db")) {
            "Fixture databases/stellar-v2-room-2.6.1.db not found on classpath"
        }
        resource.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    suspend fun assertContents(database: KitDatabase) {
        val sourceAccountId = "GB6LUXNIUDRRZELGLH6BSQ2TMICFR2YM6Z3YV7YNHV7ADZJWQHON2DYA"
        val destinationAccountId = "GCJELUJVQXPAR7PX2IIQLJJGGC3PH3QDUCWLOFYPUVZDKH3AJSKTVR34"
        val usdc = StellarAsset.Asset("USDC", sourceAccountId)

        val balances = database.balanceDao().getAll()
        assertEquals(2, balances.size)
        val nativeBalance = balances.single { it.asset == StellarAsset.Native }
        assertEquals(BigDecimal("100.5"), nativeBalance.balance)
        assertEquals(BigDecimal("1"), nativeBalance.minBalance)
        val usdcBalance = balances.single { it.asset == usdc }
        assertEquals(BigDecimal("50.25"), usdcBalance.balance)
        assertEquals(BigDecimal("0"), usdcBalance.minBalance)

        val oldest = database.operationDao().oldestOperation()
        val latest = database.operationDao().latestOperation()
        assertEquals(1L, oldest?.id)
        assertEquals("payment", oldest?.type)
        assertEquals(sourceAccountId, oldest?.payment?.from)
        assertEquals(destinationAccountId, oldest?.payment?.to)
        assertEquals(BigDecimal("10"), oldest?.payment?.amount)
        assertEquals(2L, latest?.id)
        assertEquals("create_account", latest?.type)
        assertEquals(destinationAccountId, latest?.accountCreated?.funder)
        assertEquals(sourceAccountId, latest?.accountCreated?.account)
        assertEquals(BigDecimal("5"), latest?.accountCreated?.startingBalance)

        val syncState = database.operationDao().operationSyncState()
        assertTrue(syncState?.allSynced == true)

        val dueRecords = database.rawTransactionBroadcastDao().dueRecords(nowSeconds = 1_700_000_060L)
        assertEquals(2, dueRecords.size)
        val record1 = dueRecords.single { it.txHash == "9cbf5533e70524dbdbb09bfd73efc410affe61cd57bb8c30eb1feb74845f0e62" }
        assertEquals(sourceAccountId, record1.sourceAccountId)
        assertEquals(11L, record1.sequenceNumber)
        assertEquals(2, record1.retryCount)
        assertEquals(1_700_000_060L, record1.nextRetryAt)
        assertEquals(1_700_000_000L, record1.createdAt)
        assertEquals(1_700_000_000L + 365L * 24 * 3600, record1.validUntil)
        assertTrue(record1.raw.isNotEmpty())

        val record2 = dueRecords.single { it.txHash == "3069bc7f3d2056508df90faca7734f8396eb261b1af265b91459163298564644" }
        assertEquals(destinationAccountId, record2.sourceAccountId)
        assertEquals(21L, record2.sequenceNumber)
        assertEquals(2, record2.retryCount)
    }
}

internal fun hasPlaintextSqliteHeader(file: File): Boolean {
    if (!file.isFile || file.length() < SQLITE_HEADER.size) return false
    val header = file.inputStream().use { input -> ByteArray(SQLITE_HEADER.size).also { input.read(it) } }
    return header.contentEquals(SQLITE_HEADER)
}

private val SQLITE_HEADER = "SQLite format 3\u0000".encodeToByteArray()
