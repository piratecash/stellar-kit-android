package io.horizontalsystems.stellarkit

import co.touchlab.kermit.Logger
import io.horizontalsystems.stellarkit.room.Operation
import io.horizontalsystems.stellarkit.room.OperationDao
import io.horizontalsystems.stellarkit.room.OperationInfo
import io.horizontalsystems.stellarkit.room.OperationSyncState
import io.horizontalsystems.stellarkit.room.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.stellar.sdk.Server
import org.stellar.sdk.exception.BadRequestException
import org.stellar.sdk.requests.RequestBuilder

class OperationManager(
    private val server: Server,
    private val dao: OperationDao,
    private val accountId: String,
    private val logger: Logger,
) {
    private val operationFlow = MutableSharedFlow<OperationInfoWithTags>()

    private val _syncStateFlow =
        MutableStateFlow<SyncState>(SyncState.NotSynced(StellarKit.SyncError.NotStarted))
    val syncStateFlow = _syncStateFlow.asStateFlow()

    suspend fun operationsBefore(tagQuery: TagQuery, fromId: Long?, limit: Int?): List<Operation> {
        return dao.operationsBefore(tagQuery, fromId ?: Long.MAX_VALUE, limit ?: 100)
    }

    suspend fun operationsAfter(tagQuery: TagQuery, fromId: Long?, limit: Int?): List<Operation> {
        return dao.operationsAfter( tagQuery,fromId ?: Long.MIN_VALUE, limit ?: 100)
    }

    fun operationFlow(tagQuery: TagQuery): Flow<OperationInfo> {
        var filteredOperationFlow: Flow<OperationInfoWithTags> = operationFlow.asSharedFlow()

        if (!tagQuery.isEmpty) {
            filteredOperationFlow = filteredOperationFlow.filter { info: OperationInfoWithTags ->
                info.operations.any { operationWithTags ->
                    operationWithTags.tags.any { it.conforms(tagQuery) }
                }
            }
        }

        return filteredOperationFlow.map { info ->
            OperationInfo(
                info.operations.map { it.operation },
                info.initial
            )
        }
    }

    suspend fun sync() {
        logger.d { "Syncing operations..." }

        if (_syncStateFlow.value is SyncState.Syncing) {
            logger.d { "Syncing operations is in progress" }
            return
        }

        _syncStateFlow.update {
            SyncState.Syncing
        }

        try {
            val latestOperation = dao.latestOperation()
            if (latestOperation != null) {
                logger.d { "Fetching latest operations..." }

                var pagingToken = latestOperation.pagingToken

                do {
                    val operations = getOperations(accountId, pagingToken, limit, RequestBuilder.Order.ASC)
                    logger.d { "Got latest operations: ${operations.size}, pagingToken: $pagingToken" }

                    handle(operations, false)

                    if (operations.size < limit) {
                        break
                    }

                    pagingToken = operations.last().pagingToken

                } while (true)
            }

            val operationSyncState = dao.operationSyncState()
            val allSynced = operationSyncState?.allSynced ?: false
            if (!allSynced) {
                logger.d { "Fetching history operations..." }

                val oldestOperation = dao.oldestOperation()
                var pagingToken = oldestOperation?.pagingToken
                do {
                    val operations = getOperations(accountId, pagingToken, limit, RequestBuilder.Order.DESC)
                    logger.d { "Got history operations: ${operations.size}, pagingToken: $pagingToken" }

                    handle(operations, true)

                    if (operations.size < limit) {
                        break
                    }

                    pagingToken = operations.last().pagingToken

                } while (true)

                val newOldestOperation = dao.oldestOperation()

                if (newOldestOperation != null) {
                    dao.save(OperationSyncState(allSynced = true))
                }
            }

            _syncStateFlow.update {
                SyncState.Synced
            }
        } catch (e: Throwable) {
            logger.e { "Error on OperationManager:sync() $e" }
            _syncStateFlow.update {
                SyncState.NotSynced(e)
            }
        }
    }

    private fun getOperations(
        accountId: String, pagingToken: String?, limit: Int, order: RequestBuilder.Order
    ): List<Operation> {
        val operationsRequest = server.operations()
            .forAccount(accountId)
            .limit(limit)
            .order(order)
            .cursor(pagingToken)
            .includeFailed(true)
            .includeTransactions(true)

        try {
            val execute = operationsRequest.execute()

            return execute.records.map {
                Operation.fromApi(it)
            }
        } catch (e: BadRequestException) {
            if (e.code == 404) {
                return listOf()
            }

            throw e
        }
    }

    private suspend fun handle(operations: List<Operation>, initial: Boolean) {
        if (operations.isEmpty()) return

        val operationWithTags = operations.map { operation ->
            OperationWithTags(operation, operation.tags(accountId))
        }

        val tags = operationWithTags.map { it.tags }.flatten()
        dao.saveWithTags(operations, tags)

        operationFlow.emit(OperationInfoWithTags(operationWithTags, initial))
    }

    companion object {
        private const val limit = 200
    }

    private data class OperationWithTags(
        val operation: Operation,
        val tags: List<Tag>,
    )

    private data class OperationInfoWithTags(
        val operations: List<OperationWithTags>,
        val initial: Boolean,
    )
}