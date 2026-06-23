package io.horizontalsystems.stellarkit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.stellar.sdk.Server
import org.stellar.sdk.TransactionBuilderAccount
import org.stellar.sdk.exception.BadRequestException
import org.stellar.sdk.responses.TransactionResponse

internal interface HorizonApi {
    suspend fun loadAccount(accountId: String): TransactionBuilderAccount
    suspend fun accountExists(accountId: String): Boolean
    suspend fun transactionExists(txHash: String): Boolean
    suspend fun submitTransactionXdr(envelopeXdrBase64: String): TransactionResponse
}

internal class StellarHorizonApi(private val server: Server) : HorizonApi {
    override suspend fun loadAccount(accountId: String): TransactionBuilderAccount = withContext(Dispatchers.IO) {
        server.accounts().account(accountId)
    }

    override suspend fun accountExists(accountId: String): Boolean = withContext(Dispatchers.IO) {
        existsBy404 { server.accounts().account(accountId) }
    }

    override suspend fun transactionExists(txHash: String): Boolean = withContext(Dispatchers.IO) {
        existsBy404 { server.transactions().transaction(txHash) }
    }

    override suspend fun submitTransactionXdr(envelopeXdrBase64: String): TransactionResponse =
        withContext(Dispatchers.IO) {
            server.submitTransactionXdr(envelopeXdrBase64)
        }

    private fun existsBy404(block: () -> Unit): Boolean {
        return try {
            block()
            true
        } catch (error: BadRequestException) {
            if (error.code == 404) {
                false
            } else {
                throw error
            }
        }
    }
}
