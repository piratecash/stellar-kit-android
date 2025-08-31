package io.horizontalsystems.stellarkit

import android.content.Context
import android.util.Log
import io.horizontalsystems.stellarkit.room.KitDatabase
import io.horizontalsystems.stellarkit.room.Operation
import io.horizontalsystems.stellarkit.room.OperationInfo
import io.horizontalsystems.stellarkit.room.StellarAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.stellar.sdk.Asset
import org.stellar.sdk.AssetTypeNative
import org.stellar.sdk.ChangeTrustAsset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Memo
import org.stellar.sdk.Server
import org.stellar.sdk.Transaction
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.exception.BadRequestException
import org.stellar.sdk.operations.ChangeTrustOperation
import org.stellar.sdk.operations.CreateAccountOperation
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.xdr.TransactionEnvelope
import java.math.BigDecimal

class StellarKit(
    private val keyPair: KeyPair,
    network: Network,
    db: KitDatabase,
) {
    private val stellarNetwork = network.toStellarNetwork()

    val isMainNet = network == Network.MainNet
    val sendFee: BigDecimal = BigDecimal(Transaction.MIN_BASE_FEE.toBigInteger(), 7)

    private val server = getServer(network)
    private val accountId = keyPair.accountId
    private val balancesManager = BalancesManager(
        server,
        db.balanceDao(),
        accountId
    )

    private val operationManager = OperationManager(server, db.operationDao(), accountId)
    private val updateManager = UpdateManager(server, accountId)

    val receiveAddress get() = accountId

    val operationsSyncStateFlow by operationManager::syncStateFlow
    val syncStateFlow by balancesManager::syncStateFlow
    val assetBalanceMapFlow by balancesManager::assetBalanceMapFlow

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    init {
        coroutineScope.launch {
            updateManager.updateFlow.collect {
                Log.i("AAA", "Observed update. Starting sync")
                sync()
            }
        }
    }

    fun getBalanceFlow(asset: StellarAsset) = balancesManager.getBalanceFlow(asset)

    suspend fun refresh() {
        sync()
    }

    suspend fun start() = coroutineScope {
        listOf(
            async {
                sync()
            },
            async {
                startListener()
            }
        ).awaitAll()
    }

    fun stop() {
        this.stopListener()
    }

    fun operationsBefore(tagQuery: TagQuery, fromId: Long? = null, limit: Int? = null): List<Operation> {
        return operationManager.operationsBefore(tagQuery, fromId, limit)
    }

    fun operationsAfter(tagQuery: TagQuery, fromId: Long? = null, limit: Int? = null): List<Operation> {
        return operationManager.operationsAfter(tagQuery, fromId, limit)
    }

    fun operationFlow(tagQuery: TagQuery): Flow<OperationInfo> {
        return operationManager.operationFlow(tagQuery)
    }

    private fun startListener() {
        updateManager.start()
    }

    private fun stopListener() {
        updateManager.stop()
    }

    private suspend fun sync() = coroutineScope {
        listOf(
            async {
                balancesManager.sync()
            },
            async {
                operationManager.sync()
            },
        ).awaitAll()
    }

    fun sendNative(recipient: String, amount: BigDecimal, memo: String?) {
        payment(AssetTypeNative(), recipient, amount, memo)
    }

    fun sendAsset(assetId: String, recipient: String, amount: BigDecimal, memo: String?) {
        payment(Asset.create(assetId), recipient, amount, memo)
    }

    fun createAccount(accountId: String, startingBalance: BigDecimal, memo: String?) {
        val destination = KeyPair.fromAccountId(accountId)

        val createAccountOperation = CreateAccountOperation.builder()
            .destination(destination.accountId)
            .startingBalance(startingBalance)
            .build()

        sendTransaction(createAccountOperation, memo)
    }

    fun validateEnablingAsset() {
        val balance = balancesManager.getBalance(StellarAsset.Native)

        if (balance == null) {
            throw EnablingAssetError.InsufficientBalance()
        }

        val availableBalance = balance.balance - balance.minBalance

        if (availableBalance < BalancesManager.baseReserve - sendFee) {
            throw EnablingAssetError.InsufficientBalance()
        }
    }

    fun enableAsset(assetId: String, memo: String?) {
        changeTrust(Asset.create(assetId), memo)
    }

    fun isAssetEnabled(asset: StellarAsset.Asset) = isAssetEnabled(asset, accountId)

    fun isAssetEnabled(asset: StellarAsset.Asset, accountId: String): Boolean {
        return isAssetEnabled(server, asset, accountId)
    }

    fun getEnabledAssetsCached(): List<StellarAsset.Asset> {
        return balancesManager.getAll().map { it.asset }.filterIsInstance<StellarAsset.Asset>()
    }

    private fun changeTrust(asset: Asset, memo: String?) {
        val defaultLimit = BigDecimal("922337203685.4775807") // max int64(922337203685.4775807)

        val changeTrustOperation = ChangeTrustOperation.builder()
            .asset(ChangeTrustAsset(asset))
            .limit(defaultLimit)
            .build()

        sendTransaction(changeTrustOperation, memo)
    }

    private fun payment(asset: Asset, recipient: String, amount: BigDecimal, memo: String?) {
        val destination = KeyPair.fromAccountId(recipient)

        // First, check to make sure that the destination account exists.
        // You could skip this, but if the account does not exist, you will be charged
        // the transaction fee when the transaction fails.
        // It will throw HttpResponseException if account does not exist or there was another error.
        server.accounts().account(destination.accountId)

        val paymentOperation = PaymentOperation.builder()
            .destination(destination.accountId)
            .asset(asset)
            .amount(amount)
            .build()

        sendTransaction(paymentOperation, memo)
    }

    private fun sendTransaction(operation: org.stellar.sdk.operations.Operation, memo: String?) {
        if (!keyPair.canSign()) throw WalletError.WatchOnly

        val sourceAccount = server.accounts().account(accountId)

        val transactionBuilder = TransactionBuilder(sourceAccount, stellarNetwork)
            .addOperation(operation)
            .setTimeout(180)
            .setBaseFee(Transaction.MIN_BASE_FEE)

        memo?.let {
            transactionBuilder.addMemo(Memo.text(memo))
        }

        sendTransaction(transactionBuilder.build())
    }

    private fun sendTransaction(transaction: Transaction) {
        if (!keyPair.canSign()) throw WalletError.WatchOnly

        transaction.sign(keyPair)

        try {
            val response = server.submitTransaction(transaction)
            Log.e("AAA", "Success! $response")
        } catch (e: Exception) {
            Log.e("AAA", "Something went wrong!", e)
            throw e
        }
    }

    fun sendTransaction(transactionEnvelope: String) {
        val transaction = Transaction.fromEnvelopeXdr(transactionEnvelope, stellarNetwork)
        check(transaction is Transaction)

        sendTransaction(transaction)
    }

    fun signTransaction(transactionEnvelope: String): String {
        val transaction = Transaction.fromEnvelopeXdr(transactionEnvelope, stellarNetwork)
        if (!keyPair.canSign()) throw WalletError.WatchOnly

        transaction.sign(keyPair)

        return transaction.toEnvelopeXdrBase64()
    }

    fun getTransaction(transactionEnvelope: String): Transaction {
        return Transaction.fromEnvelopeXdr(transactionEnvelope, stellarNetwork) as Transaction
    }

    fun doesAccountExist(accountId: String) = try {
        val destination = KeyPair.fromAccountId(accountId)
        server.accounts().account(destination.accountId)
        true
    } catch (e: BadRequestException) {
        false
    } catch (e: Throwable) {
        throw e
    }

    sealed class SyncError : Error() {
        data object NotStarted : SyncError() {
            override val message = "Not Started"
        }
    }

    sealed class WalletError : Error() {
        data object WatchOnly : WalletError()
    }

    companion object {
        fun getInstance(
            stellarWallet: StellarWallet,
            network: Network,
            context: Context,
            walletId: String,
        ): StellarKit {
            val keyPair = getKeyPair(stellarWallet)

            val db = KitDatabase.getInstance(context, "stellar-${walletId}-${network.name}")
            return StellarKit(keyPair, network, db)
        }

        fun getAccountId(stellarWallet: StellarWallet): String {
            val keyPair = getKeyPair(stellarWallet)
            return keyPair.accountId
        }

        private fun getKeyPair(stellarWallet: StellarWallet): KeyPair = when (stellarWallet) {
            is StellarWallet.Seed -> KeyPair.fromBip39Seed(stellarWallet.seed, 0)
            is StellarWallet.WatchOnly -> KeyPair.fromAccountId(stellarWallet.addressStr)
            is StellarWallet.SecretKey -> KeyPair.fromSecretSeed(stellarWallet.secretSeed)
        }

        fun validateAddress(address: String) {
            KeyPair.fromAccountId(address)
        }

        fun isValidSecretKey(key: String) = try {
            KeyPair.fromSecretSeed(key)
            true
        } catch (e: IllegalArgumentException) {
            false
        }

        fun getSecretSeed(stellarWallet: StellarWallet) =
            getKeyPair(stellarWallet).secretSeed?.let {
                String(it)
            }

        fun estimateFee(transactionEnvelope: String): BigDecimal? {
            val envelope = TransactionEnvelope.fromXdrBase64(transactionEnvelope)
            val fee = envelope.v1?.tx?.fee ?: envelope.v0?.tx?.fee

            return fee?.let {
                fee.uint32.number.toBigInteger().toBigDecimal(7)
            }
        }

        fun isAssetEnabled(network: Network, asset: StellarAsset.Asset, accountId: String): Boolean {
            return isAssetEnabled(getServer(network), asset, accountId)
        }

        private fun getServer(network: Network): Server {
            val serverUrl = when (network) {
                Network.MainNet -> "https://horizon.stellar.org"
                Network.TestNet -> "https://horizon-testnet.stellar.org"
            }

            return Server(serverUrl)
        }

        private fun isAssetEnabled(server: Server, asset: StellarAsset.Asset, accountId: String): Boolean {
            try {
                val account = server.accounts().account(accountId)

                return account.balances.any {
                    asset.code == it.assetCode && asset.issuer == it.assetIssuer
                }
            } catch (e: BadRequestException) {
                if (e.code == 404) {
                    return false
                }
                throw e
            }
        }
    }
}

sealed class EnablingAssetError: Throwable() {
    class InsufficientBalance: EnablingAssetError()
}