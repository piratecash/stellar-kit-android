package io.horizontalsystems.stellarkit

import io.horizontalsystems.stellarkit.models.RawTransactionBroadcastStatus
import io.horizontalsystems.stellarkit.models.RawTransactionRetryMetadata
import io.horizontalsystems.stellarkit.room.RawTransactionBroadcastRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.stellar.sdk.Account
import org.stellar.sdk.exception.BadRequestException
import org.stellar.sdk.responses.Problem
import org.stellar.sdk.responses.TransactionResponse
import org.stellar.sdk.xdr.CreateAccountResult
import org.stellar.sdk.xdr.CreateAccountResultCode
import org.stellar.sdk.xdr.Int64
import org.stellar.sdk.xdr.OperationResult
import org.stellar.sdk.xdr.OperationResultCode
import org.stellar.sdk.xdr.OperationType
import org.stellar.sdk.xdr.PaymentResult
import org.stellar.sdk.xdr.PaymentResultCode
import org.stellar.sdk.xdr.TransactionResult
import org.stellar.sdk.xdr.TransactionResultCode
import java.io.IOException

class RawTransactionBroadcasterTest {
    private lateinit var dao: InMemoryRawTransactionBroadcastDao
    private lateinit var horizonApi: FakeHorizonApi
    private lateinit var broadcaster: RawTransactionBroadcaster

    @Before
    fun setUp() {
        dao = InMemoryRawTransactionBroadcastDao()
        horizonApi = FakeHorizonApi()
        broadcaster = RawTransactionBroadcaster(
            horizonApi = horizonApi,
            dao = dao,
            network = RawTransactionTestData.network,
            nowSeconds = { NOW_SECONDS },
            networkTimeoutMs = 50,
        )
    }

    @Test
    fun broadcast_success_returnsSubmittedWithoutQueue() = runTest {
        val decoded = decodedTransaction()
        horizonApi.submitResponse = RawTransactionTestData.response(decoded.txHash)

        val result = broadcaster.broadcast(decoded.raw, metadata(decoded))

        assertEquals(decoded.txHash, result.txHash)
        assertEquals(RawTransactionBroadcastStatus.Submitted, result.status)
        assertTrue(dao.records.isEmpty())
    }

    @Test
    fun broadcast_responseHashMismatch_returnsSubmittedWithLocalHash() = runTest {
        val decoded = decodedTransaction()
        horizonApi.submitResponse = RawTransactionTestData.response("a".repeat(64))

        val result = broadcaster.broadcast(decoded.raw, metadata(decoded))

        assertEquals(decoded.txHash, result.txHash)
        assertEquals(RawTransactionBroadcastStatus.Submitted, result.status)
    }

    @Test
    fun broadcast_transientWithMetadata_queues() = runTest {
        val decoded = decodedTransaction()
        horizonApi.submitError = IOException("network")

        val result = broadcaster.broadcast(decoded.raw, metadata(decoded))

        assertEquals(RawTransactionBroadcastStatus.Queued, result.status)
        val record = dao.records.single()
        assertEquals(decoded.txHash, record.txHash)
        assertEquals(decoded.raw.toList(), record.raw.toList())
        assertEquals(decoded.sourceAccountId, record.sourceAccountId)
        assertEquals(decoded.sequenceNumber, record.sequenceNumber)
        assertEquals(decoded.validUntil, record.validUntil)
        assertEquals(NOW_SECONDS, record.createdAt)
    }

    @Test
    fun broadcast_transientWithoutMetadata_throwsWithoutQueue() = runTest {
        val decoded = decodedTransaction()
        horizonApi.submitError = IOException("network")

        assertFailsWith<IOException> {
            broadcaster.broadcast(decoded.raw, retryMetadata = null)
        }
        assertTrue(dao.records.isEmpty())
    }

    @Test
    fun broadcast_timeout_queuesAndAllowsNextAttempt() = runTest {
        val decoded = decodedTransaction()
        horizonApi.submitBlock = {
            delay(1_000)
            RawTransactionTestData.response(decoded.txHash)
        }

        val result = broadcaster.broadcast(decoded.raw, metadata(decoded))

        assertEquals(RawTransactionBroadcastStatus.Queued, result.status)

        horizonApi.submitBlock = { RawTransactionTestData.response(decoded.txHash) }
        val secondResult = broadcaster.broadcast(decoded.raw, metadata(decoded))

        assertEquals(RawTransactionBroadcastStatus.Submitted, secondResult.status)
    }

    @Test
    fun broadcast_presenceCheckTimeout_afterTransientSubmit_queues() = runTest {
        val decoded = decodedTransaction()
        horizonApi.submitError = IOException("network")
        horizonApi.transactionExistsBlock = {
            delay(1_000)
            false
        }

        val result = broadcaster.broadcast(decoded.raw, metadata(decoded))

        assertEquals(RawTransactionBroadcastStatus.Queued, result.status)
        assertEquals(decoded.txHash, dao.records.single().txHash)
    }

    @Test
    fun broadcast_expired_throwsWithoutQueue() = runTest {
        val decoded = decodedTransaction()
        val expiredBroadcaster = RawTransactionBroadcaster(
            horizonApi = horizonApi,
            dao = dao,
            network = RawTransactionTestData.network,
            nowSeconds = { checkNotNull(decoded.validUntil) },
            networkTimeoutMs = 50,
        )

        assertFailsWith<TransactionError.RawTransactionExpired> {
            expiredBroadcaster.broadcast(decoded.raw, metadata(decoded))
        }
        assertTrue(dao.records.isEmpty())
        assertEquals(0, horizonApi.submitCount)
    }

    @Test
    fun broadcast_knownSubmitted_returnsSubmittedWithoutQueue() = runTest {
        val decoded = decodedTransaction()
        horizonApi.submitError = IOException("network")
        horizonApi.transactionExists = true

        val result = broadcaster.broadcast(decoded.raw, metadata(decoded))

        assertEquals(RawTransactionBroadcastStatus.Submitted, result.status)
        assertEquals(decoded.txHash, result.txHash)
        assertTrue(dao.records.isEmpty())
    }

    @Test
    fun broadcast_badAuth_isPermanent() = runTest {
        val decoded = decodedTransaction()
        horizonApi.submitError = badRequest(transactionResult(TransactionResultCode.txBAD_AUTH))

        assertFailsWith<BadRequestException> {
            broadcaster.broadcast(decoded.raw, metadata(decoded))
        }
        assertTrue(dao.records.isEmpty())
    }

    @Test
    fun broadcast_paymentNoDestination_isPermanent() = runTest {
        val decoded = decodedTransaction()
        horizonApi.submitError = badRequest(paymentNoDestinationResult())

        assertFailsWith<BadRequestException> {
            broadcaster.broadcast(decoded.raw, metadata(decoded))
        }
        assertTrue(dao.records.isEmpty())
    }

    @Test
    fun broadcast_createAccountAlreadyExists_isPermanent() = runTest {
        val decoded = decodedTransaction()
        horizonApi.submitError = badRequest(createAccountAlreadyExistsResult())

        assertFailsWith<BadRequestException> {
            broadcaster.broadcast(decoded.raw, metadata(decoded))
        }
        assertTrue(dao.records.isEmpty())
    }

    @Test
    fun retry_sequenceConsumed_deletesWithoutBroadcast() = runTest {
        val decoded = decodedTransaction()
        dao.insert(record(decoded))
        horizonApi.sequenceNumber = decoded.sequenceNumber

        broadcaster.retryQueued()

        assertEquals(listOf(decoded.txHash), dao.deletedTxHashes)
        assertEquals(0, horizonApi.submitCount)
    }

    @Test
    fun retry_success_deletesRecord() = runTest {
        val decoded = decodedTransaction()
        dao.insert(record(decoded))
        horizonApi.sequenceNumber = decoded.sequenceNumber - 1
        horizonApi.submitResponse = RawTransactionTestData.response(decoded.txHash)

        broadcaster.retryQueued()

        assertEquals(listOf(decoded.txHash), dao.deletedTxHashes)
        assertEquals(1, horizonApi.submitCount)
    }

    @Test
    fun retry_hangingRequest_updatesRetryStateAndContinuesBatch() = runTest {
        val first = decodedTransaction()
        val second = decodedTransaction(sequenceNumber = 8)
        dao.insert(record(first))
        dao.insert(record(second))
        horizonApi.sequenceNumber = first.sequenceNumber - 1
        horizonApi.submitBlock = {
            if (horizonApi.submitCount == 1) {
                delay(1_000)
            }
            RawTransactionTestData.response(second.txHash)
        }

        broadcaster.retryQueued()

        assertEquals(first.txHash, dao.updatedRecords.single().txHash)
        assertEquals(listOf(second.txHash), dao.deletedTxHashes)
    }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun retryQueued_alreadyRunning_skipsConcurrentCall() = runTest {
        val decoded = decodedTransaction()
        dao.insert(record(decoded))
        horizonApi.sequenceNumber = decoded.sequenceNumber - 1
        horizonApi.submitBlock = {
            delay(10)
            RawTransactionTestData.response(decoded.txHash)
        }

        val retryJob = launch {
            broadcaster.retryQueued()
        }
        runCurrent()

        broadcaster.retryQueued()

        assertEquals(1, horizonApi.submitCount)
        advanceUntilIdle()
        retryJob.join()
        assertEquals(1, horizonApi.submitCount)
        assertEquals(listOf(decoded.txHash), dao.deletedTxHashes)
    }

    private fun decodedTransaction(sequenceNumber: Long = 1): DecodedRawStellarTransaction {
        val transaction = RawTransactionTestData.signedPayment(sequenceNumber = sequenceNumber)
        return RawTransactionUtils.decode(RawTransactionUtils.rawBytes(transaction), RawTransactionTestData.network)
    }

    private fun metadata(decoded: DecodedRawStellarTransaction) = RawTransactionRetryMetadata(
        sourceAccountId = decoded.sourceAccountId,
        sequenceNumber = decoded.sequenceNumber,
        validUntil = checkNotNull(decoded.validUntil),
    )

    private fun record(decoded: DecodedRawStellarTransaction) = RawTransactionBroadcastRecord(
        txHash = decoded.txHash,
        raw = decoded.raw,
        sourceAccountId = decoded.sourceAccountId,
        sequenceNumber = decoded.sequenceNumber,
        validUntil = checkNotNull(decoded.validUntil),
        createdAt = NOW_SECONDS,
        retryCount = 0,
        nextRetryAt = NOW_SECONDS,
    )

    private fun badRequest(result: TransactionResult): BadRequestException {
        val extras = Problem.Extras(
            null,
            null,
            result.toXdrBase64(),
            Problem.Extras.ResultCodes(null, null, emptyList())
        )
        val problem = Problem("type", "title", 400, "detail", extras)
        return BadRequestException(400, "body", problem, null)
    }

    private fun transactionResult(code: TransactionResultCode): TransactionResult =
        TransactionResult.builder()
            .feeCharged(Int64(0L))
            .ext(TransactionResult.TransactionResultExt(0))
            .result(
                TransactionResult.TransactionResultResult.builder()
                    .discriminant(code)
                    .build()
            )
            .build()

    private fun paymentNoDestinationResult(): TransactionResult {
        val paymentResult = PaymentResult(PaymentResultCode.PAYMENT_NO_DESTINATION)
        val operation = OperationResult.builder()
            .discriminant(OperationResultCode.opINNER)
            .tr(
                OperationResult.OperationResultTr.builder()
                    .discriminant(OperationType.PAYMENT)
                    .paymentResult(paymentResult)
                    .build()
            )
            .build()
        return TransactionResult.builder()
            .feeCharged(Int64(0L))
            .ext(TransactionResult.TransactionResultExt(0))
            .result(
                TransactionResult.TransactionResultResult.builder()
                    .discriminant(TransactionResultCode.txFAILED)
                    .results(arrayOf(operation))
                    .build()
            )
            .build()
    }

    private fun createAccountAlreadyExistsResult(): TransactionResult {
        val createAccountResult = CreateAccountResult(CreateAccountResultCode.CREATE_ACCOUNT_ALREADY_EXIST)
        val operation = OperationResult.builder()
            .discriminant(OperationResultCode.opINNER)
            .tr(
                OperationResult.OperationResultTr.builder()
                    .discriminant(OperationType.CREATE_ACCOUNT)
                    .createAccountResult(createAccountResult)
                    .build()
            )
            .build()
        return TransactionResult.builder()
            .feeCharged(Int64(0L))
            .ext(TransactionResult.TransactionResultExt(0))
            .result(
                TransactionResult.TransactionResultResult.builder()
                    .discriminant(TransactionResultCode.txFAILED)
                    .results(arrayOf(operation))
                    .build()
            )
            .build()
    }

    private suspend inline fun <reified T : Throwable> assertFailsWith(block: suspend () -> Unit): T {
        return try {
            block()
            fail("Expected ${T::class.java.simpleName}")
            throw AssertionError("unreachable")
        } catch (error: Throwable) {
            if (error is T) {
                error
            } else {
                throw error
            }
        }
    }

    private class FakeHorizonApi : HorizonApi {
        var submitResponse: TransactionResponse = RawTransactionTestData.response("0".repeat(64))
        var submitError: Throwable? = null
        var submitBlock: (suspend () -> TransactionResponse)? = null
        var transactionExistsBlock: (suspend () -> Boolean)? = null
        var submitCount = 0
        var transactionExists = false
        var sequenceNumber = 1L

        override suspend fun loadAccount(accountId: String) = Account(accountId, sequenceNumber)
        override suspend fun accountExists(accountId: String) = true
        override suspend fun transactionExists(txHash: String) =
            transactionExistsBlock?.invoke() ?: transactionExists

        override suspend fun submitTransactionXdr(envelopeXdrBase64: String): TransactionResponse {
            submitCount += 1
            submitError?.let { throw it }
            submitBlock?.let { return it() }
            return submitResponse
        }
    }

    private companion object {
        const val NOW_SECONDS = 1_000L
    }
}
