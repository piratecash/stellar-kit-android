package io.horizontalsystems.stellarkit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.stellarkit.room.KitDatabase
import io.horizontalsystems.stellarkit.room.RawTransactionBroadcastRecord
import io.horizontalsystems.stellarkit.room.StellarAsset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.stellar.sdk.Server
import org.stellar.sdk.TransactionBuilderAccount
import org.stellar.sdk.responses.TransactionResponse
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(StellarSdkTestRunner::class)
class StellarKitDestroyTest {
    private val horizon = MockWebServer()
    private val horizonApi = FakeHorizonApi()
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private var previousUncaughtHandler: Thread.UncaughtExceptionHandler? = null

    @Volatile
    private var streamOperationEvents = false

    private lateinit var roomExecutor: ExecutorService
    private lateinit var db: KitDatabase
    private lateinit var kit: StellarKit

    @Before
    fun setUp() {
        previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
        horizon.dispatcher = HorizonDispatcher()
        horizon.start()
        roomExecutor = Executors.newSingleThreadExecutor()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KitDatabase::class.java)
            .setQueryCoroutineContext(roomExecutor.asCoroutineDispatcher())
            .build()
        kit = StellarKit(
            signer = WatchOnlySigner(SOURCE_PUBLIC_KEY),
            network = Network.TestNet,
            db = db,
            server = Server(horizon.url("/").toString()),
            horizonApi = horizonApi,
        )
    }

    @After
    fun tearDown() {
        runBlocking { kit.destroy() }
        horizon.shutdown()
        roomExecutor.shutdownNow()
        Thread.setDefaultUncaughtExceptionHandler(previousUncaughtHandler)
    }

    @Test
    fun destroy_afterStart_closesDatabase() = runBlocking {
        kit.start()
        assertTrue(db.isOpen)

        kit.destroy()

        assertFalse(db.isOpen)
    }

    @Test
    fun destroy_callerAlreadyCancelled_stillClosesDatabase() = runBlocking {
        kit.start()
        assertTrue(db.isOpen)

        val cancelledJob = Job().apply { cancel() }
        launch(cancelledJob, start = CoroutineStart.ATOMIC) {
            kit.destroy()
        }.join()

        assertFalse(db.isOpen)
    }

    @Test
    fun destroy_syncInFlight_waitsForKitCoroutinesBeforeClosing() = runBlocking {
        streamOperationEvents = true
        kit.start()
        queueDueRecord()
        horizonApi.hold(ignoreCancellation = true)
        withTimeout(TIMEOUT_MS) { horizonApi.checking.await() }

        val destroying = async(Dispatchers.Default) { kit.destroy() }
        delay(SETTLE_MS)
        assertTrue(destroying.isActive)
        assertTrue(db.isOpen)

        horizonApi.release()
        withTimeout(TIMEOUT_MS) { destroying.await() }

        assertFalse(db.isOpen)
        assertEquals(emptyList<Throwable>(), uncaught)
    }

    @Test
    fun destroy_calledTwice_noError() = runBlocking {
        kit.refresh()
        kit.destroy()
        kit.destroy()

        assertFalse(db.isOpen)
    }

    @Test
    fun refresh_afterDestroy_doesNotThrow() = runBlocking {
        queueDueRecord()
        kit.destroy()

        kit.refresh()
    }

    @Test
    fun refresh_callerCancelled_propagatesCancellation() = runBlocking {
        queueDueRecord()
        horizonApi.hold(ignoreCancellation = false)
        var completedNormally = false

        val refreshing = launch(Dispatchers.Default) {
            kit.refresh()
            completedNormally = true
        }
        withTimeout(TIMEOUT_MS) { horizonApi.checking.await() }
        refreshing.cancelAndJoin()

        assertTrue(refreshing.isCancelled)
        assertFalse(completedNormally)
    }

    @Test
    fun retryQueued_dbFailureOnLiveKit_queueKeptForNextSync() = runBlocking {
        queueDueRecord()
        execSQL("ALTER TABLE RawTransactionBroadcastRecord RENAME TO ParkedRecord")

        kit.refresh()

        execSQL("ALTER TABLE ParkedRecord RENAME TO RawTransactionBroadcastRecord")
        assertEquals(emptyList<String>(), horizonApi.checkedTxHashes)

        kit.refresh()

        assertEquals(listOf(TX_HASH), horizonApi.checkedTxHashes)
        assertEquals(emptyList<RawTransactionBroadcastRecord>(), db.rawTransactionBroadcastDao().dueRecords(NOW_SECONDS))
    }

    @Test
    fun getBalanceFlow_queryOverlappingDestroy_noExceptionEscapes() = runBlocking {
        val roomReleased = holdRoomExecutor()
        val collection = Collection(this, kit.getBalanceFlow(StellarAsset.Native))
        delay(SETTLE_MS)

        kit.destroy()
        roomReleased.countDown()
        withTimeout(TIMEOUT_MS) { collection.job.join() }

        assertNull(collection.failure)
    }

    @Test
    fun getBalanceFlow_idleCollectorAfterDestroy_endsOnlyWithItsScope() = runBlocking {
        val collection = Collection(this, kit.getBalanceFlow(StellarAsset.Native))
        withTimeout(TIMEOUT_MS) { collection.firstValue.await() }

        kit.destroy()
        delay(SETTLE_MS)
        assertTrue(collection.job.isActive)

        collection.job.cancelAndJoin()
        assertNull(collection.failure)
    }

    @Test
    fun getBalanceFlow_collectedAfterDestroy_completesWithoutException() = runBlocking {
        kit.destroy()

        val collection = Collection(this, kit.getBalanceFlow(StellarAsset.Native))
        withTimeout(TIMEOUT_MS) { collection.job.join() }

        assertNull(collection.failure)
    }

    @Test
    fun getBalanceFlow_dbClosedOnLiveKit_propagatesFailure() = runBlocking {
        db.close()

        val collection = Collection(this, kit.getBalanceFlow(StellarAsset.Native))
        withTimeout(TIMEOUT_MS) { collection.job.join() }

        assertNotNull(collection.failure)
    }

    @Test
    fun operationsBefore_afterDestroy_throws() = runBlocking {
        kit.destroy()

        val error = try {
            kit.operationsBefore(TagQuery(null, null, null))
            null
        } catch (e: Throwable) {
            e
        }

        assertTrue("Room surfaced $error", error is CancellationException)
    }

    private suspend fun queueDueRecord() {
        db.rawTransactionBroadcastDao().insert(
            RawTransactionBroadcastRecord(
                txHash = TX_HASH,
                raw = Base64.getDecoder().decode(SIGNED_ENVELOPE),
                sourceAccountId = SOURCE_ACCOUNT_ID,
                sequenceNumber = 11,
                validUntil = VALID_UNTIL,
                createdAt = 0,
                retryCount = 0,
                nextRetryAt = 0,
            )
        )
    }

    private fun execSQL(sql: String) = db.openHelper.writableDatabase.execSQL(sql)

    // Queues every later Room task behind a blocked one until the latch is released.
    private fun holdRoomExecutor() = CountDownLatch(1).also { latch -> roomExecutor.execute { latch.await() } }

    private class Collection(scope: CoroutineScope, flow: Flow<*>) {
        val firstValue = CompletableDeferred<Unit>()

        @Volatile
        var failure: Throwable? = null

        val job = scope.launch(Dispatchers.Default) {
            try {
                flow.collect { firstValue.complete(Unit) }
            } catch (e: Throwable) {
                if (isActive) failure = e else throw e
            }
        }
    }

    private class FakeHorizonApi : HorizonApi {
        val checkedTxHashes = CopyOnWriteArrayList<String>()
        val checking = CompletableDeferred<Unit>()

        @Volatile
        private var gate: CompletableDeferred<Unit>? = null

        @Volatile
        private var ignoreCancellation = false

        fun hold(ignoreCancellation: Boolean) {
            this.ignoreCancellation = ignoreCancellation
            gate = CompletableDeferred()
        }

        fun release() {
            gate?.complete(Unit)
        }

        override suspend fun transactionExists(txHash: String): Boolean {
            checkedTxHashes += txHash
            checking.complete(Unit)
            gate?.let { gate ->
                if (ignoreCancellation) withContext(NonCancellable) { gate.await() } else gate.await()
            }
            return true
        }

        override suspend fun loadAccount(accountId: String): TransactionBuilderAccount =
            throw UnsupportedOperationException()

        override suspend fun accountExists(accountId: String): Boolean =
            throw UnsupportedOperationException()

        override suspend fun submitTransactionXdr(envelopeXdrBase64: String): TransactionResponse =
            throw UnsupportedOperationException()
    }

    // Horizon knows no account; the operations stream optionally pushes events to trigger the kit's own sync.
    private inner class HorizonDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            if (request.getHeader("Accept") != "text/event-stream") {
                return MockResponse().setResponseCode(404).setBody(NOT_FOUND)
            }
            val events = if (streamOperationEvents) OPERATION_EVENT.repeat(STREAM_EVENTS) else ""
            return MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(events)
                .throttleBody(OPERATION_EVENT.length.toLong(), STREAM_EVENT_INTERVAL_MS, TimeUnit.MILLISECONDS)
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val SETTLE_MS = 300L
        const val STREAM_EVENTS = 50
        const val STREAM_EVENT_INTERVAL_MS = 100L
        const val NOW_SECONDS = 1_700_000_000L
        const val VALID_UNTIL = 4_102_444_800L
        const val NOT_FOUND = """{"status":404,"title":"Resource Missing"}"""
        const val OPERATION_EVENT = "data: {\"id\":\"1\",\"paging_token\":\"1\",\"type_i\":0,\"type\":\"create_account\"}\n\n"

        // Real signed envelope from the stellar-v2-room-2.6.1.db fixture; no KeyPair is created under Robolectric.
        const val SIGNED_ENVELOPE =
            "AAAAAgAAAAB8ul2ooOMckWZZ/BlDU2IEWOsM9neK/w09fgHlNoHc3QAAAGQAAAAAAAAACwAAAAEAAAAAAAAAAAAAAABquyQ9AAAAAA" +
                "AAAAEAAAAAAAAAAQAAAABq26d+kcGbVgEL+fh/onfdH+SmbRAGcuTB4/SR7yTjKwAAAAAAAAAAAJiWgAAAAAAAAAABNoHc3QAA" +
                "AEAT5ddv3fWkKk64vQQr4/HUv7BnPZK8TbCXu+ZD0LT6XyQau+Q31QUgKnoPXEKDcVwRnJALrKcEyS2CTo16xwkG"
        const val TX_HASH = "9cbf5533e70524dbdbb09bfd73efc410affe61cd57bb8c30eb1feb74845f0e62"
        const val SOURCE_ACCOUNT_ID = "GB6LUXNIUDRRZELGLH6BSQ2TMICFR2YM6Z3YV7YNHV7ADZJWQHON2DYA"
        val SOURCE_PUBLIC_KEY: ByteArray = "7cba5da8a0e31c916659fc194353620458eb0cf6778aff0d3d7e01e53681dcdd"
            .chunked(2)
            .map { it.toInt(16).toByte() }
            .toByteArray()
    }
}
