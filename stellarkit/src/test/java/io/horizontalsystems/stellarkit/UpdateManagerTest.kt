package io.horizontalsystems.stellarkit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.stellarkit.room.KitDatabase
import io.horizontalsystems.stellarkit.room.Operation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.stellar.sdk.Server
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(StellarSdkTestRunner::class)
class UpdateManagerTest {
    private val horizon = MockWebServer()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val streamCursors = CopyOnWriteArrayList<String>()
    private val streamRequests = AtomicInteger()
    private val ledgerRequests = AtomicInteger()
    private val operationStreamed = CompletableDeferred<Unit>()
    private val updateAfterOperationStreamed = CompletableDeferred<Unit>()

    @Volatile
    private var onStream: (cursor: String, index: Int) -> MockResponse = { _, _ -> FakeHorizon.idleStream() }

    @Volatile
    private var onLedgers: (index: Int) -> MockResponse = { FakeHorizon.ledgers(LEDGER) }

    private lateinit var roomExecutor: ExecutorService
    private lateinit var db: KitDatabase
    private lateinit var manager: UpdateManager

    @Before
    fun setUp() {
        horizon.dispatcher = HorizonDispatcher()
        horizon.start()
        roomExecutor = Executors.newSingleThreadExecutor()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KitDatabase::class.java)
            .setQueryCoroutineContext(roomExecutor.asCoroutineDispatcher())
            .build()
        manager = UpdateManager(Server(horizon.url("/").toString()), FakeHorizon.ACCOUNT_ID, db.operationDao())
    }

    @After
    fun tearDown() {
        manager.destroy()
        scope.cancel()
        Thread.sleep(SETTLE_MS)
        horizon.shutdown()
        db.close()
        roomExecutor.shutdownNow()
    }

    @Test
    fun start_horizonClosesIdleStream_operationLandedDuringReconnectEmitsUpdate() = runBlocking {
        saveOperation(OLD_OPERATION)
        operationLandsAfterFirstStream(NEW_OPERATION)
        collectUpdates()

        manager.start()

        assertUpdateForLandedOperation()
    }

    @Test
    fun start_emptyHistory_streamsFromLatestLedgerCursor() = runBlocking {
        operationLandsAfterFirstStream(NEW_OPERATION)
        collectUpdates()

        manager.start()

        assertEquals("$LEDGER", awaitStreamCursors(1).firstOrNull())
        assertUpdateForLandedOperation()
    }

    @Test
    fun reconnectAfterFailure_reopensFromLatestPersistedOperation() = runBlocking {
        saveOperation(OLD_OPERATION)
        onStream = { _, index -> if (index == 1) FakeHorizon.serverError() else FakeHorizon.idleStream() }

        manager.start()
        awaitStreamCursors(1)
        saveOperation(NEW_OPERATION)

        val cursors = awaitStreamCursors(2)
        assertEquals("stream cursors: $cursors", "$NEW_OPERATION", cursors.getOrNull(1))
    }

    @Test
    fun stop_duringCursorResolution_noStreamOpened() = runBlocking {
        onLedgers = { FakeHorizon.ledgers(LEDGER).setHeadersDelay(LEDGER_DELAY_MS, TimeUnit.MILLISECONDS) }

        manager.start()
        manager.stop()
        delay(LEDGER_DELAY_MS + SETTLE_MS)

        assertEquals(emptyList<String>(), streamCursors.toList())
    }

    @Test
    fun start_calledTwiceDuringCursorResolution_stopLeavesNoLiveStream() = runBlocking {
        onLedgers = { FakeHorizon.ledgers(LEDGER).setHeadersDelay(LEDGER_DELAY_MS, TimeUnit.MILLISECONDS) }

        manager.start()
        manager.start()
        delay(LEDGER_DELAY_MS + SETTLE_MS)
        assertTrue("no stream opened before stop", streamCursors.isNotEmpty())
        manager.stop()
        delay(SETTLE_MS)
        val requestsAfterStop = streamCursors.size
        delay(OBSERVATION_MS)

        assertEquals("stream cursors: $streamCursors", requestsAfterStop, streamCursors.size)
    }

    @Test
    fun start_cursorResolutionFails_retriesWithBackoffThenStreams() = runBlocking {
        onLedgers = { index -> if (index == 1) FakeHorizon.serverError() else FakeHorizon.ledgers(LEDGER) }

        manager.start()

        val cursors = awaitStreamCursors(1)
        assertEquals("ledger lookups: ${ledgerRequests.get()}", "$LEDGER", cursors.firstOrNull())
        assertEquals(2, ledgerRequests.get())
    }

    @Test
    fun openStream_afterReconnect_emitsUpdateWithoutStreamEvent() = runBlocking {
        onLedgers = { index -> if (index == 1) FakeHorizon.serverError() else FakeHorizon.ledgers(LATER_LEDGER) }
        val updates = collectUpdates()

        manager.start()
        withTimeoutOrNull(TIMEOUT_MS) { while (updates.get() == 0) delay(POLL_MS) }
        delay(SETTLE_MS)

        assertEquals("stream cursors: $streamCursors", 1, updates.get())
    }

    // Horizon serves the operation only to a stream that reconnects with a cursor older than it.
    private fun operationLandsAfterFirstStream(pagingToken: Long) {
        onStream = { cursor, index ->
            val older = cursor.toLongOrNull()?.let { it < pagingToken } == true
            if (index > 1 && older) {
                operationStreamed.complete(Unit)
                FakeHorizon.operationStream(pagingToken)
            } else {
                FakeHorizon.idleStream()
            }
        }
    }

    private suspend fun assertUpdateForLandedOperation() {
        val updated = withTimeoutOrNull(TIMEOUT_MS) { updateAfterOperationStreamed.await() }
        assertNotNull("no update for the landed operation; stream cursors: $streamCursors", updated)
    }

    private fun collectUpdates(): AtomicInteger {
        val updates = AtomicInteger()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            manager.updateFlow.collect {
                updates.incrementAndGet()
                if (operationStreamed.isCompleted) updateAfterOperationStreamed.complete(Unit)
            }
        }
        return updates
    }

    private suspend fun awaitStreamCursors(count: Int): List<String> {
        withTimeoutOrNull(TIMEOUT_MS) { while (streamCursors.size < count) delay(POLL_MS) }
        return streamCursors.toList()
    }

    private suspend fun saveOperation(pagingToken: Long) {
        db.operationDao().save(
            listOf(
                Operation(
                    id = pagingToken,
                    timestamp = 0,
                    pagingToken = "$pagingToken",
                    sourceAccount = FakeHorizon.ACCOUNT_ID,
                    transactionHash = "",
                    transactionSuccessful = true,
                    fee = null,
                    memo = null,
                    type = "create_account",
                    payment = null,
                    accountCreated = null,
                    changeTrust = null,
                )
            )
        )
    }

    private inner class HorizonDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = when {
            FakeHorizon.isStream(request) -> {
                val cursor = request.requestUrl?.queryParameter("cursor").orEmpty()
                streamCursors += cursor
                onStream(cursor, streamRequests.incrementAndGet())
            }
            request.requestUrl?.encodedPath == "/ledgers" -> onLedgers(ledgerRequests.incrementAndGet())
            else -> FakeHorizon.notFound()
        }
    }

    private companion object {
        const val OLD_OPERATION = 100L
        const val LEDGER = 200L
        const val LATER_LEDGER = 250L
        const val NEW_OPERATION = 300L
        const val TIMEOUT_MS = 5_000L
        const val SETTLE_MS = 300L
        const val POLL_MS = 20L
        const val LEDGER_DELAY_MS = 500L
        const val OBSERVATION_MS = 1_000L
    }
}

/** Minimal Horizon responses for stream tests; Horizon closes an idle stream with `byebye`. */
internal object FakeHorizon {
    const val ACCOUNT_ID = "GB6LUXNIUDRRZELGLH6BSQ2TMICFR2YM6Z3YV7YNHV7ADZJWQHON2DYA"

    private const val HELLO = "event: open\ndata: \"hello\"\n\n"
    private const val BYEBYE = "event: close\ndata: \"byebye\"\n\n"

    fun isStream(request: RecordedRequest) = request.getHeader("Accept") == "text/event-stream"

    fun idleStream() = stream(HELLO + BYEBYE)

    fun operationStream(vararg pagingTokens: Long) = stream(HELLO + pagingTokens.joinToString("") { operationEvent(it) })

    fun ledgers(pagingToken: Long) = MockResponse()
        .setHeader("Content-Type", "application/hal+json")
        .setBody(
            """{"_links":{"self":{"href":""},"next":{"href":""},"prev":{"href":""}},""" +
                """"_embedded":{"records":[{"id":"$pagingToken","paging_token":"$pagingToken","sequence":$pagingToken}]}}"""
        )

    fun notFound() = MockResponse().setResponseCode(404).setBody("""{"status":404,"title":"Resource Missing"}""")

    fun serverError() = MockResponse().setResponseCode(500).setBody("""{"status":500,"title":"Internal Server Error"}""")

    private fun operationEvent(pagingToken: Long) =
        "id: $pagingToken\ndata: {\"id\":\"$pagingToken\",\"paging_token\":\"$pagingToken\",\"type_i\":0,\"type\":\"create_account\"}\n\n"

    private fun stream(body: String) = MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body)
}
