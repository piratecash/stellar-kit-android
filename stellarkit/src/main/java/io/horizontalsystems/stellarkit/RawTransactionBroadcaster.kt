package io.horizontalsystems.stellarkit

import io.horizontalsystems.stellarkit.models.RawTransactionBroadcastResult
import io.horizontalsystems.stellarkit.models.RawTransactionBroadcastStatus
import io.horizontalsystems.stellarkit.models.RawTransactionRetryMetadata
import io.horizontalsystems.stellarkit.room.RawTransactionBroadcastDao
import io.horizontalsystems.stellarkit.room.RawTransactionBroadcastRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.stellar.sdk.exception.BadRequestException
import org.stellar.sdk.exception.NetworkException
import org.stellar.sdk.exception.RequestTimeoutException
import org.stellar.sdk.exception.TooManyRequestsException
import org.stellar.sdk.responses.Problem
import org.stellar.sdk.xdr.CreateAccountResultCode
import org.stellar.sdk.xdr.OperationResult
import org.stellar.sdk.xdr.OperationResultCode
import org.stellar.sdk.xdr.OperationType
import org.stellar.sdk.xdr.PaymentResultCode
import org.stellar.sdk.xdr.TransactionResult
import org.stellar.sdk.xdr.TransactionResultCode
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import org.stellar.sdk.Network as StellarSdkNetwork

internal class RawTransactionBroadcaster(
    private val horizonApi: HorizonApi,
    private val dao: RawTransactionBroadcastDao,
    private val network: StellarSdkNetwork,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    private val networkTimeoutMs: Long = DEFAULT_NETWORK_TIMEOUT_MS,
) {
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val retryRunning = AtomicBoolean(false)

    suspend fun broadcast(
        rawTransaction: ByteArray,
        retryMetadata: RawTransactionRetryMetadata?,
    ): RawTransactionBroadcastResult {
        val decoded = RawTransactionUtils.decode(rawTransaction, network)
        if (!inFlight.add(decoded.txHash)) {
            throw TransactionError.BroadcastAlreadyInProgress
        }

        return try {
            broadcastDecoded(decoded, retryMetadata)
        } finally {
            inFlight.remove(decoded.txHash)
        }
    }

    suspend fun transactionExists(txHash: String): Boolean =
        withNetworkTimeout {
            horizonApi.transactionExists(txHash)
        }

    suspend fun retryQueued() {
        if (!retryRunning.compareAndSet(false, true)) return

        try {
            val now = nowSeconds()
            dao.deleteExpired(now)
            dao.dueRecords(now).forEach { record ->
                retry(record)
            }
        } finally {
            retryRunning.set(false)
        }
    }

    private suspend fun broadcastDecoded(
        decoded: DecodedRawStellarTransaction,
        retryMetadata: RawTransactionRetryMetadata?,
    ): RawTransactionBroadcastResult {
        validateRetryMetadata(decoded, retryMetadata)
        requireNotExpired(decoded.validUntil)

        return try {
            submit(decoded)
            submitted(decoded)
        } catch (error: TimeoutCancellationException) {
            handleBroadcastError(error, decoded, retryMetadata)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            handleBroadcastError(error, decoded, retryMetadata)
        }
    }

    private suspend fun retry(record: RawTransactionBroadcastRecord) {
        val decoded = try {
            RawTransactionUtils.decode(record.raw, network)
        } catch (error: TransactionError.InvalidRawTransaction) {
            dao.delete(record.txHash)
            return
        }

        try {
            if (transactionExists(record.txHash) || sequenceConsumed(record)) {
                dao.delete(record.txHash)
                return
            }

            submit(decoded)
            dao.delete(record.txHash)
        } catch (error: TimeoutCancellationException) {
            updateRetry(record)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            handleRetryError(error, decoded, record)
        }
    }

    private fun validateRetryMetadata(
        decoded: DecodedRawStellarTransaction,
        retryMetadata: RawTransactionRetryMetadata?,
    ) {
        retryMetadata ?: return

        if (decoded.sourceAccountId != retryMetadata.sourceAccountId ||
            decoded.sequenceNumber != retryMetadata.sequenceNumber ||
            decoded.validUntil != retryMetadata.validUntil
        ) {
            throw TransactionError.RetryMetadataMismatch
        }
    }

    private fun requireNotExpired(validUntil: Long?) {
        if (validUntil != null && nowSeconds() >= validUntil) {
            throw TransactionError.RawTransactionExpired
        }
    }

    private suspend fun submit(decoded: DecodedRawStellarTransaction) {
        withNetworkTimeout {
            horizonApi.submitTransactionXdr(decoded.envelopeXdrBase64)
        }
    }

    private fun submitted(decoded: DecodedRawStellarTransaction) = RawTransactionBroadcastResult(
        txHash = decoded.txHash,
        status = RawTransactionBroadcastStatus.Submitted,
    )

    private fun alreadyKnown(decoded: DecodedRawStellarTransaction) = RawTransactionBroadcastResult(
        txHash = decoded.txHash,
        status = RawTransactionBroadcastStatus.AlreadyKnown,
    )

    private suspend fun handleBroadcastError(
        error: Throwable,
        decoded: DecodedRawStellarTransaction,
        retryMetadata: RawTransactionRetryMetadata?,
    ): RawTransactionBroadcastResult {
        // The submit itself failed (e.g. timeout/network error), but Horizon already
        // has this tx hash from a previous attempt - it was actually accepted, not lost.
        if (isKnownSubmitted(decoded)) return alreadyKnown(decoded)
        if (isPermanent(error, decoded)) throw error

        retryMetadata ?: throw error
        queue(decoded, retryMetadata)

        return RawTransactionBroadcastResult(
            txHash = decoded.txHash,
            status = RawTransactionBroadcastStatus.Queued,
        )
    }

    private suspend fun handleRetryError(
        error: Throwable,
        decoded: DecodedRawStellarTransaction,
        record: RawTransactionBroadcastRecord,
    ) {
        when {
            isKnownSubmitted(decoded) || isPermanent(error, decoded) -> dao.delete(record.txHash)
            else -> updateRetry(record)
        }
    }

    private fun queue(
        decoded: DecodedRawStellarTransaction,
        retryMetadata: RawTransactionRetryMetadata,
    ) {
        val now = nowSeconds()
        dao.insert(
            RawTransactionBroadcastRecord(
                txHash = decoded.txHash,
                raw = decoded.raw,
                sourceAccountId = retryMetadata.sourceAccountId,
                sequenceNumber = retryMetadata.sequenceNumber,
                validUntil = retryMetadata.validUntil,
                createdAt = now,
                retryCount = 0,
                nextRetryAt = nextRetryAt(now, retryCount = 1),
            )
        )
    }

    private fun updateRetry(record: RawTransactionBroadcastRecord) {
        val now = nowSeconds()
        dao.update(record.retried(nextRetryAt(now, record.retryCount + 1)))
    }

    private suspend fun isKnownSubmitted(decoded: DecodedRawStellarTransaction): Boolean =
        try {
            transactionExists(decoded.txHash)
        } catch (error: TimeoutCancellationException) {
            false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            false
        }

    private suspend fun isPermanent(
        error: Throwable,
        decoded: DecodedRawStellarTransaction,
    ): Boolean {
        return when (transactionResultCode(error)) {
            TransactionResultCode.txTOO_LATE,
            TransactionResultCode.txBAD_AUTH,
            TransactionResultCode.txBAD_AUTH_EXTRA,
            TransactionResultCode.txINSUFFICIENT_FEE,
            TransactionResultCode.txNO_ACCOUNT,
            TransactionResultCode.txINSUFFICIENT_BALANCE,
            TransactionResultCode.txMISSING_OPERATION,
            TransactionResultCode.txNOT_SUPPORTED,
            TransactionResultCode.txMALFORMED,
            TransactionResultCode.txBAD_SPONSORSHIP,
            TransactionResultCode.txBAD_MIN_SEQ_AGE_OR_GAP,
            TransactionResultCode.txSOROBAN_INVALID -> true
            TransactionResultCode.txBAD_SEQ -> sequenceConsumedOrFalse(decoded)
            TransactionResultCode.txFAILED -> hasPermanentOperationFailure(error)
            TransactionResultCode.txINTERNAL_ERROR,
            TransactionResultCode.txTOO_EARLY,
            TransactionResultCode.txSUCCESS,
            TransactionResultCode.txFEE_BUMP_INNER_SUCCESS,
            TransactionResultCode.txFEE_BUMP_INNER_FAILED,
            null -> false
        }
    }

    private fun transactionResultCode(error: Throwable): TransactionResultCode? {
        val extras = (error as? BadRequestException)?.problem?.extras ?: return null
        val parsed = parseTransactionResult(extras)
        if (parsed != null) return parsed.result.discriminant

        return extras.resultCodes?.transactionResultCode?.toEnumNameOrNull<TransactionResultCode>()
    }

    private fun hasPermanentOperationFailure(error: Throwable): Boolean {
        val extras = (error as? BadRequestException)?.problem?.extras ?: return false
        val parsed = parseTransactionResult(extras)
        if (parsed != null) {
            return parsed.result.results.orEmpty().any(::isPermanentOperationResult)
        }

        return extras.resultCodes?.operationsResultCodes.orEmpty()
            .map { it.normalizedCode() }
            .any { it in PERMANENT_OPERATION_STRING_CODES }
    }

    private fun isPermanentOperationResult(result: OperationResult): Boolean {
        return when (result.discriminant) {
            OperationResultCode.opINNER -> isPermanentInnerOperationResult(result)
            OperationResultCode.opBAD_AUTH,
            OperationResultCode.opNO_ACCOUNT,
            OperationResultCode.opNOT_SUPPORTED,
            OperationResultCode.opTOO_MANY_SUBENTRIES,
            OperationResultCode.opEXCEEDED_WORK_LIMIT,
            OperationResultCode.opTOO_MANY_SPONSORING -> true
        }
    }

    private fun isPermanentInnerOperationResult(result: OperationResult): Boolean {
        val tr = result.tr ?: return true
        return when (tr.discriminant) {
            OperationType.PAYMENT -> tr.paymentResult?.discriminant in permanentPaymentCodes
            OperationType.CREATE_ACCOUNT -> tr.createAccountResult?.discriminant in permanentCreateAccountCodes
            else -> true
        }
    }

    private fun parseTransactionResult(extras: Problem.Extras): TransactionResult? =
        try {
            extras.parseResultXdr()
        } catch (error: Throwable) {
            null
        }

    private suspend fun sequenceConsumed(record: RawTransactionBroadcastRecord): Boolean =
        currentSequence(record.sourceAccountId) >= record.sequenceNumber

    private suspend fun sequenceConsumedOrFalse(decoded: DecodedRawStellarTransaction): Boolean =
        try {
            currentSequence(decoded.sourceAccountId) >= decoded.sequenceNumber
        } catch (error: TimeoutCancellationException) {
            false
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            false
        }

    private suspend fun currentSequence(sourceAccountId: String): Long =
        withNetworkTimeout {
            horizonApi.loadAccount(sourceAccountId).sequenceNumber
        }

    private suspend fun <T> withNetworkTimeout(block: suspend () -> T): T =
        withTimeout(networkTimeoutMs) {
            block()
        }

    private fun nextRetryAt(now: Long, retryCount: Int): Long =
        now + (RETRY_DELAY_SECONDS * retryCount.coerceAtMost(MAX_RETRY_MULTIPLIER))

    private inline fun <reified T : Enum<T>> String.toEnumNameOrNull(): T? {
        val normalized = normalizedCode()
        return enumValues<T>().firstOrNull { it.name.normalizedCode() == normalized }
    }

    private fun String.normalizedCode(): String =
        replace("_", "").uppercase()

    private companion object {
        const val DEFAULT_NETWORK_TIMEOUT_MS = 20_000L
        const val RETRY_DELAY_SECONDS = 10L
        const val MAX_RETRY_MULTIPLIER = 6

        val permanentPaymentCodes = setOf(
            PaymentResultCode.PAYMENT_MALFORMED,
            PaymentResultCode.PAYMENT_UNDERFUNDED,
            PaymentResultCode.PAYMENT_SRC_NO_TRUST,
            PaymentResultCode.PAYMENT_SRC_NOT_AUTHORIZED,
            PaymentResultCode.PAYMENT_NO_DESTINATION,
            PaymentResultCode.PAYMENT_NO_TRUST,
            PaymentResultCode.PAYMENT_NOT_AUTHORIZED,
            PaymentResultCode.PAYMENT_LINE_FULL,
            PaymentResultCode.PAYMENT_NO_ISSUER,
        )

        val permanentCreateAccountCodes = setOf(
            CreateAccountResultCode.CREATE_ACCOUNT_MALFORMED,
            CreateAccountResultCode.CREATE_ACCOUNT_UNDERFUNDED,
            CreateAccountResultCode.CREATE_ACCOUNT_LOW_RESERVE,
            CreateAccountResultCode.CREATE_ACCOUNT_ALREADY_EXIST,
        )

        val PERMANENT_OPERATION_STRING_CODES = setOf(
            "OPBADAUTH",
            "OPNOACCOUNT",
            "OPNOTSUPPORTED",
            "OPTOOMANYSUBENTRIES",
            "OPEXCEEDEDWORKLIMIT",
            "OPTOOMANYSPONSORING",
            "PAYMENTMALFORMED",
            "PAYMENTUNDERFUNDED",
            "PAYMENTSRCNOTRUST",
            "PAYMENTSRCNOTAUTHORIZED",
            "PAYMENTNODESTINATION",
            "PAYMENTNOTRUST",
            "PAYMENTNOTAUTHORIZED",
            "PAYMENTLINEFULL",
            "PAYMENTNOISSUER",
            "OPMALFORMED",
            "OPUNDERFUNDED",
            "OPNODESTINATION",
            "OPNOTRUST",
            "OPNOTAUTHORIZED",
            "OPLINEFULL",
            "OPNOISSUER",
            "CREATEACCOUNTMALFORMED",
            "CREATEACCOUNTUNDERFUNDED",
            "CREATEACCOUNTLOWRESERVE",
            "CREATEACCOUNTALREADYEXIST",
            "OPALREADYEXIST",
            "OPLOWRESERVE",
        )
    }
}
