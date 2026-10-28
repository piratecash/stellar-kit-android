package io.horizontalsystems.stellarkit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.stellarkit.room.KitDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Server
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(StellarSdkTestRunner::class)
class StellarKitUpdateConflationTest {
    private val horizon = MockWebServer()
    private val operationSyncs = AtomicInteger()
    private val burstStreamed = CompletableDeferred<Unit>()

    @Volatile
    private var burstReady = false

    private lateinit var roomExecutor: ExecutorService
    private lateinit var db: KitDatabase
    private lateinit var kit: StellarKit

    @Before
    fun setUp() {
        horizon.dispatcher = HorizonDispatcher()
        horizon.start()
        roomExecutor = Executors.newSingleThreadExecutor()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), KitDatabase::class.java)
            .setQueryCoroutineContext(roomExecutor.asCoroutineDispatcher())
            .build()
        val server = Server(horizon.url("/").toString())
        kit = StellarKit(
            signer = WatchOnlySigner(KeyPair.fromAccountId(FakeHorizon.ACCOUNT_ID).publicKey),
            network = Network.TestNet,
            db = db,
            server = server,
            horizonApi = StellarHorizonApi(server),
        )
    }

    @After
    fun tearDown() {
        runBlocking { kit.destroy() }
        horizon.shutdown()
        roomExecutor.shutdownNow()
    }

    @Test
    fun updateFlow_burstOfStreamEvents_syncsConflated() = runBlocking {
        kit.start()
        // Only after the start sync: while it runs, OperationManager skips every overlapping sync.
        burstReady = true
        assertNotNull("burst never streamed", withTimeoutOrNull(TIMEOUT_MS) { burstStreamed.await() })
        delay(OBSERVATION_MS)

        assertTrue("operation syncs: ${operationSyncs.get()}", operationSyncs.get() <= MAX_OPERATION_SYNCS)
    }

    private inner class HorizonDispatcher : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.requestUrl?.encodedPath.orEmpty()
            return when {
                FakeHorizon.isStream(request) -> if (burstReady && burstStreamed.complete(Unit)) {
                    FakeHorizon.operationStream(*LongArray(BURST_EVENTS) { it + 1L })
                } else {
                    FakeHorizon.idleStream()
                }
                path == "/ledgers" -> FakeHorizon.ledgers(LEDGER)
                path.endsWith("/operations") -> {
                    operationSyncs.incrementAndGet()
                    FakeHorizon.notFound().setHeadersDelay(SLOW_SYNC_MS, TimeUnit.MILLISECONDS)
                }
                else -> FakeHorizon.notFound()
            }
        }
    }

    private companion object {
        const val BURST_EVENTS = 20
        const val MAX_OPERATION_SYNCS = 3
        const val LEDGER = 200L
        const val SLOW_SYNC_MS = 200L
        const val TIMEOUT_MS = 5_000L
        const val OBSERVATION_MS = 3_000L
    }
}
