package io.horizontalsystems.stellarkit

import io.horizontalsystems.stellarkit.room.OperationDao
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.stellar.sdk.Server
import org.stellar.sdk.requests.EventListener
import org.stellar.sdk.requests.RequestBuilder
import org.stellar.sdk.requests.SSEStream
import org.stellar.sdk.responses.operations.OperationResponse
import java.util.Optional
import kotlin.math.min

class UpdateManager(
    private val server: Server,
    private val accountId: String,
    private val dao: OperationDao,
) {

    private val _updateFlow = MutableSharedFlow<Unit>()
    val updateFlow = _updateFlow.asSharedFlow()

    // Serialized dispatcher view (parallelism = 1): the mutable state below is
    // touched only through this scope, so updates never overlap and run in
    // submission order — start()/stop()/destroy() keep their call order without
    // extra locking. Not a dedicated thread: tasks may run on different Default
    // pool threads, but never concurrently.
    private val coroutineScope = CoroutineScope(Dispatchers.Default.limitedParallelism(1))

    private var operationsRequest: SSEStream<OperationResponse>? = null
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private var started = false

    fun start() {
        coroutineScope.launch { openStream() }
    }

    fun stop() {
        coroutineScope.launch { closeStream() }
    }

    fun destroy() {
        // Tear down through the same serialized scope so it can never interleave
        // with an in-flight openStream(); cancel the scope only once the current
        // stream is closed.
        coroutineScope.launch {
            closeStream()
            coroutineScope.cancel()
        }
    }

    private suspend fun openStream() {
        started = true
        val cursor = try {
            // Not "now": a reconnect with "now" skips operations that landed while the stream was down.
            dao.latestOperation()?.pagingToken ?: latestLedgerPagingToken()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            scheduleReconnect()
            return
        }
        if (!started) return
        // Each SSEStream owns a dedicated single-thread scheduled executor (its
        // reconnect watchdog) plus an OkHttpClient, both released only by close().
        // Close the previous stream before opening a new one so they never accumulate.
        operationsRequest?.close()
        operationsRequest = server.operations()
            .forAccount(accountId)
            .includeFailed(true)
            .cursor(cursor)
            .stream(object : EventListener<OperationResponse> {
                override fun onEvent(operationResponse: OperationResponse) {
                    coroutineScope.launch {
                        reconnectAttempt = 0
                        _updateFlow.emit(Unit)
                    }
                }

                override fun onFailure(error: Optional<Throwable>, responseCode: Optional<Int>) {
                    scheduleReconnect()
                }
            })
        _updateFlow.emit(Unit)
    }

    private suspend fun latestLedgerPagingToken(): String = withContext(Dispatchers.IO) {
        server.ledgers().order(RequestBuilder.Order.DESC).limit(1).execute().records.first().pagingToken
    }

    private fun scheduleReconnect() {
        coroutineScope.launch {
            if (!started || reconnectJob?.isActive == true) return@launch
            val delayMs = reconnectDelayMs(reconnectAttempt++)
            reconnectJob = currentCoroutineContext()[Job]
            delay(delayMs)
            if (started) openStream()
        }
    }

    private fun closeStream() {
        started = false
        reconnectJob?.cancel()
        reconnectJob = null
        operationsRequest?.close()
        operationsRequest = null
    }

    private fun reconnectDelayMs(attempt: Int): Long =
        min(MAX_RECONNECT_DELAY_MS, BASE_RECONNECT_DELAY_MS shl attempt.coerceAtMost(MAX_BACKOFF_SHIFT))

    private companion object {
        const val BASE_RECONNECT_DELAY_MS = 1_000L
        const val MAX_RECONNECT_DELAY_MS = 60_000L
        const val MAX_BACKOFF_SHIFT = 6
    }
}
