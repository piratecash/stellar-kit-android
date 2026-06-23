package io.horizontalsystems.stellarkit

import androidx.room.DatabaseConfiguration
import androidx.room.InvalidationTracker
import androidx.sqlite.db.SupportSQLiteQuery
import androidx.sqlite.db.SupportSQLiteOpenHelper
import io.horizontalsystems.stellarkit.room.AssetBalance
import io.horizontalsystems.stellarkit.room.BalanceDao
import io.horizontalsystems.stellarkit.room.KitDatabase
import io.horizontalsystems.stellarkit.room.Operation
import io.horizontalsystems.stellarkit.room.OperationDao
import io.horizontalsystems.stellarkit.room.OperationSyncState
import io.horizontalsystems.stellarkit.room.StellarAsset
import io.horizontalsystems.stellarkit.room.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.stellar.sdk.Account
import org.stellar.sdk.Asset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Transaction
import org.stellar.sdk.TransactionBuilderAccount
import org.stellar.sdk.operations.CreateAccountOperation
import org.stellar.sdk.operations.PaymentOperation
import java.math.BigDecimal
import java.util.Base64
import org.stellar.sdk.Network as StellarSdkNetwork

class StellarKitSigningTest {
    private lateinit var database: KitDatabase
    private lateinit var sourceKeyPair: KeyPair
    private lateinit var horizonApi: FakeHorizonApi
    private lateinit var kit: StellarKit

    @Before
    fun setUp() {
        database = FakeKitDatabase()
        sourceKeyPair = KeyPair.random()
        horizonApi = FakeHorizonApi(sourceKeyPair.accountId)
        kit = StellarKit(
            signer = KeyPairSigner(sourceKeyPair),
            network = Network.TestNet,
            db = database,
            horizonApi = horizonApi,
        )
    }

    @After
    fun tearDown() {
        if (::kit.isInitialized) {
            kit.destroy()
        }
    }

    @Test
    fun signedNative_existingAccount_buildsPaymentWithoutSubmit() = runTest {
        horizonApi.destinationExists = true
        val destination = KeyPair.random()

        val signed = kit.signedNative(destination.accountId, BigDecimal.ONE, memo = null)
        val transaction = transactionFrom(signed.raw)

        assertTrue(transaction.operations.single() is PaymentOperation)
        assertEquals(signed.txHash, transaction.hashHex())
        assertEquals(sourceKeyPair.accountId, signed.sourceAccountId)
        assertEquals(transaction.sequenceNumber, signed.sequenceNumber)
        assertEquals(
            RawTransactionUtils.decode(signed.raw, RawTransactionTestData.network).validUntil,
            signed.validUntil,
        )
        assertEquals(0, horizonApi.submitCount)
    }

    @Test
    fun signedNative_missingAccount_buildsCreateAccountWithoutSubmit() = runTest {
        horizonApi.destinationExists = false
        val destination = KeyPair.random()

        val signed = kit.signedNative(destination.accountId, BigDecimal.ONE, memo = null)
        val transaction = transactionFrom(signed.raw)

        assertTrue(transaction.operations.single() is CreateAccountOperation)
        assertEquals(signed.txHash, transaction.hashHex())
        assertEquals(0, horizonApi.submitCount)
    }

    @Test
    fun signedAsset_validAsset_buildsAssetPaymentWithoutSubmit() = runTest {
        val issuer = KeyPair.random()
        val destination = KeyPair.random()

        val signed = kit.signedAsset("USDC:${issuer.accountId}", destination.accountId, BigDecimal.ONE, memo = null)
        val transaction = transactionFrom(signed.raw)
        val operation = transaction.operations.single()

        assertTrue(operation is PaymentOperation)
        assertEquals(Asset.create("USDC:${issuer.accountId}"), (operation as PaymentOperation).asset)
        assertEquals(signed.txHash, transaction.hashHex())
        assertEquals(0, horizonApi.submitCount)
    }

    @Test
    fun signedNative_watchOnly_throwsWatchOnly() = runTest {
        val watchOnlyKit = StellarKit(
            signer = WatchOnlySigner(sourceKeyPair.publicKey),
            network = Network.TestNet,
            db = database,
            horizonApi = horizonApi,
        )

        assertFailsWith<StellarKit.WalletError.WatchOnly> {
            watchOnlyKit.signedNative(KeyPair.random().accountId, BigDecimal.ONE, memo = null)
        }
        assertEquals(0, horizonApi.submitCount)
    }

    @Test
    fun sendNative_existingAccount_submitsSignedEnvelope() = runTest {
        horizonApi.destinationExists = true
        val destination = KeyPair.random()

        kit.sendNative(destination.accountId, BigDecimal.ONE, memo = null)

        val transaction = transactionFromBase64(horizonApi.submittedEnvelopes.single())
        assertTrue(transaction.operations.single() is PaymentOperation)
        assertEquals(1, horizonApi.submitCount)
    }

    @Test
    fun sendAsset_existingAccount_submitsSignedEnvelope() = runTest {
        val issuer = KeyPair.random()
        val destination = KeyPair.random()

        kit.sendAsset("USDC:${issuer.accountId}", destination.accountId, BigDecimal.ONE, memo = null)

        val operation = transactionFromBase64(horizonApi.submittedEnvelopes.single()).operations.single()
        assertTrue(operation is PaymentOperation)
        assertEquals(Asset.create("USDC:${issuer.accountId}"), (operation as PaymentOperation).asset)
        assertEquals(1, horizonApi.submitCount)
    }

    private fun transactionFrom(raw: ByteArray): Transaction {
        val envelope = Base64.getEncoder().encodeToString(raw)
        return transactionFromBase64(envelope)
    }

    private fun transactionFromBase64(envelope: String): Transaction {
        return Transaction.fromEnvelopeXdr(envelope, StellarSdkNetwork.TESTNET) as Transaction
    }

    private class FakeKitDatabase : KitDatabase() {
        private val balanceDao = FakeBalanceDao()
        private val operationDao = FakeOperationDao()
        private val rawDao = InMemoryRawTransactionBroadcastDao()

        override fun balanceDao(): BalanceDao = balanceDao
        override fun operationDao(): OperationDao = operationDao
        override fun rawTransactionBroadcastDao() = rawDao
        override fun clearAllTables() = Unit
        override fun createInvalidationTracker() = InvalidationTracker(
            this,
            "AssetBalance",
            "Operation",
            "OperationSyncState",
            "Tag",
            "RawTransactionBroadcastRecord",
        )

        override fun createOpenHelper(config: DatabaseConfiguration): SupportSQLiteOpenHelper {
            throw UnsupportedOperationException("Fake database does not support open helpers")
        }
    }

    private class FakeBalanceDao : BalanceDao {
        override fun delete() = Unit
        override fun insertAll(balances: List<AssetBalance>) = Unit
        override fun getAssetBalancesFlow(): Flow<List<AssetBalance>> = flowOf(emptyList())
        override fun getBalance(asset: StellarAsset): AssetBalance? = null
        override fun getAll(): List<AssetBalance> = emptyList()
    }

    private class FakeOperationDao : OperationDao {
        override fun operations(query: SupportSQLiteQuery): List<Operation> = emptyList()
        override fun latestOperation(): Operation? = null
        override fun operationSyncState(): OperationSyncState? = null
        override fun oldestOperation(): Operation? = null
        override fun save(operationSyncState: OperationSyncState) = Unit
        override fun save(operations: List<Operation>) = Unit
        override fun deleteTags(operationIds: List<Long>) = Unit
        override fun insertTags(tags: List<Tag>) = Unit
    }

    private class FakeHorizonApi(private val sourceAccountId: String) : HorizonApi {
        var destinationExists = true
        var submitCount = 0
        val submittedEnvelopes = mutableListOf<String>()

        override suspend fun loadAccount(accountId: String): TransactionBuilderAccount {
            if (accountId != sourceAccountId && !destinationExists) {
                throw AssertionError("Destination account lookup should not be used for create-account signing")
            }

            return Account(accountId, 1)
        }

        override suspend fun accountExists(accountId: String): Boolean = destinationExists
        override suspend fun transactionExists(txHash: String): Boolean = false

        override suspend fun submitTransactionXdr(envelopeXdrBase64: String) =
            RawTransactionTestData.response("0".repeat(64)).also {
                submitCount += 1
                submittedEnvelopes.add(envelopeXdrBase64)
            }
    }

    private suspend inline fun <reified T : Throwable> assertFailsWith(block: suspend () -> Unit): T {
        return try {
            block()
            throw AssertionError("Expected ${T::class.java.simpleName}")
        } catch (error: Throwable) {
            if (error is T) {
                error
            } else {
                throw error
            }
        }
    }
}
