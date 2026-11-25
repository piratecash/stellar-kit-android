package io.horizontalsystems.stellarkit

import io.horizontalsystems.stellarkit.room.AssetBalance
import io.horizontalsystems.stellarkit.room.KitDatabase
import io.horizontalsystems.stellarkit.room.Operation
import io.horizontalsystems.stellarkit.room.OperationSyncState
import io.horizontalsystems.stellarkit.room.RawTransactionBroadcastRecord
import io.horizontalsystems.stellarkit.room.StellarAsset
import io.horizontalsystems.stellarkit.room.Tag
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Server
import org.stellar.sdk.TransactionBuilderAccount
import org.stellar.sdk.responses.TransactionResponse
import java.io.File
import java.math.BigDecimal

class StellarKitDesktopSmokeTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun getInstance_desktop_writesAndReadsThroughAllDaos() = runBlocking {
        val database = KitDatabase.getInstance(PlatformContext(tmp.root), DB_NAME, DATABASE_KEY)
        try {
            val balanceDao = database.balanceDao()
            val balances = listOf(
                AssetBalance(StellarAsset.Native, BigDecimal("10.5"), BigDecimal.ONE),
                AssetBalance(USDC, BigDecimal("3"), BigDecimal.ZERO),
            )
            balanceDao.deleteAllAndInsertNew(balances)
            assertEquals(balances.toSet(), balanceDao.getAssetBalancesFlow().first().toSet())
            assertEquals(balances[1], balanceDao.getBalance(USDC))

            val operationDao = database.operationDao()
            operationDao.saveWithTags(
                operations = listOf(operation(1), operation(2)),
                tags = listOf(tag(1, Tag.Type.Incoming), tag(2, Tag.Type.Outgoing)),
            )
            operationDao.save(OperationSyncState(allSynced = true))
            assertEquals(listOf(operation(1)), operationDao.operationsBefore(incomingNative, null, 10))
            assertEquals(listOf(operation(1)), operationDao.operationsAfter(incomingNative, 0, 10))
            assertEquals(true, operationDao.operationSyncState()?.allSynced)

            val broadcastDao = database.rawTransactionBroadcastDao()
            val record = RawTransactionBroadcastRecord(
                txHash = "hash",
                raw = byteArrayOf(1, 2, 3),
                sourceAccountId = "source",
                sequenceNumber = 7,
                validUntil = 200,
                createdAt = 100,
                retryCount = 0,
                nextRetryAt = 100,
            )
            broadcastDao.insert(record)
            assertEquals(listOf(record), broadcastDao.dueRecords(nowSeconds = 150))
        } finally {
            database.close()
        }

        assertTrue(File(tmp.root, DB_NAME).exists())
    }

    @Test
    fun destroy_watchOnlyKitOnDesktop_laterDaoCallFails() = runBlocking<Unit> {
        val database = KitDatabase.getInstance(PlatformContext(tmp.root), DB_NAME, DATABASE_KEY)
        val kit = StellarKit(
            signer = WatchOnlySigner(KeyPair.random().publicKey),
            network = Network.TestNet,
            db = database,
            server = Server("http://127.0.0.1:1"),
            horizonApi = UnusedHorizonApi,
        )

        kit.destroy()

        // Room 2.8.4 has no isOpen off Android; a closed database fails the next query instead.
        assertThrows(Exception::class.java) {
            runBlocking { database.balanceDao().getAll() }
        }
    }

    private fun operation(id: Long) = Operation(
        id = id,
        timestamp = id,
        pagingToken = id.toString(),
        sourceAccount = "source",
        transactionHash = "hash-$id",
        transactionSuccessful = true,
        fee = BigDecimal.ONE,
        memo = null,
        type = "create_account",
        payment = null,
        accountCreated = Operation.AccountCreated(BigDecimal.TEN, "funder", "account"),
        changeTrust = null,
    )

    private fun tag(operationId: Long, type: Tag.Type) =
        Tag(operationId, type, StellarAsset.Native.id, listOf("funder"))

    private object UnusedHorizonApi : HorizonApi {
        override suspend fun loadAccount(accountId: String): TransactionBuilderAccount = error("unused")
        override suspend fun accountExists(accountId: String): Boolean = error("unused")
        override suspend fun transactionExists(txHash: String): Boolean = error("unused")
        override suspend fun submitTransactionXdr(envelopeXdrBase64: String): TransactionResponse = error("unused")
    }

    private companion object {
        const val DB_NAME = "stellar-desktop-smoke-TestNet"
        val DATABASE_KEY = ByteArray(32) { it.toByte() }
        val USDC = StellarAsset.Asset("USDC", "GA5ZSEJYB37JRC5AVCIA5MOP4RHTM335X2KGX3IHOJAPP5RE34K4KZVN")
        val incomingNative = TagQuery(Tag.Type.Incoming, StellarAsset.Native.id, null)
    }
}
