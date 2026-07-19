package io.horizontalsystems.stellarkit

import android.content.Context
import android.util.Log
import io.horizontalsystems.stellarkit.models.RawTransactionBroadcastResult
import io.horizontalsystems.stellarkit.models.RawTransactionRetryMetadata
import io.horizontalsystems.stellarkit.models.SignedRawStellarTransaction
import io.horizontalsystems.stellarkit.room.KitDatabase
import io.horizontalsystems.stellarkit.room.Operation
import io.horizontalsystems.stellarkit.room.OperationInfo
import io.horizontalsystems.stellarkit.room.StellarAsset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import okhttp3.EventListener
import org.stellar.sdk.Asset
import org.stellar.sdk.AssetTypeNative
import org.stellar.sdk.AbstractTransaction
import org.stellar.sdk.ChangeTrustAsset
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Memo
import org.stellar.sdk.Server
import org.stellar.sdk.Transaction
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.exception.BadRequestException
import org.stellar.sdk.operations.ChangeTrustOperation
import org.stellar.sdk.operations.CreateAccountOperation
import org.stellar.sdk.operations.Operation as StellarOperation
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.responses.TransactionResponse
import org.stellar.sdk.xdr.TransactionEnvelope
import java.math.BigDecimal

class StellarKit private constructor(
    private val signer: Signer,
    network: Network,
    db: KitDatabase,
    private val server: Server,
    private val horizonApi: HorizonApi,
    private val rawTransactionBroadcaster: RawTransactionBroadcaster,
) {
    constructor(
        signer: Signer,
        network: Network,
        db: KitDatabase,
    ) : this(signer, network, db, getServer(network))

    internal constructor(
        signer: Signer,
        network: Network,
        db: KitDatabase,
        horizonApi: HorizonApi,
    ) : this(signer, network, db, getServer(network), horizonApi)

    private constructor(
        signer: Signer,
        network: Network,
        db: KitDatabase,
        server: Server,
    ) : this(signer, network, db, server, StellarHorizonApi(server))

    private constructor(
        signer: Signer,
        network: Network,
        db: KitDatabase,
        server: Server,
        horizonApi: HorizonApi,
    ) : this(
        signer = signer,
        network = network,
        db = db,
        server = server,
        horizonApi = horizonApi,
        rawTransactionBroadcaster = RawTransactionBroadcaster(
            horizonApi = horizonApi,
            dao = db.rawTransactionBroadcastDao(),
            network = network.toStellarNetwork(),
        ),
    )

    private val stellarNetwork = network.toStellarNetwork()

    val isMainNet = network == Network.MainNet
    val sendFee: BigDecimal = BigDecimal(Transaction.MIN_BASE_FEE.toBigInteger(), 7)

    private val accountId = KeyPair.fromPublicKey(signer.publicKey).accountId
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

    /**
     * Permanently tears down this kit instance. Unlike [stop] (a restartable
     * pause), this cancels the kit's coroutine scope and the update listener for
     * good. Call it when the instance is discarded; it must not be reused after.
     */
    fun destroy() {
        updateManager.destroy()
        coroutineScope.cancel()
    }

    fun operationsBefore(
        tagQuery: TagQuery,
        fromId: Long? = null,
        limit: Int? = null
    ): List<Operation> {
        return operationManager.operationsBefore(tagQuery, fromId, limit)
    }

    fun operationsAfter(
        tagQuery: TagQuery,
        fromId: Long? = null,
        limit: Int? = null
    ): List<Operation> {
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
            async {
                rawTransactionBroadcaster.retryQueued()
            },
        ).awaitAll()
    }

    suspend fun sendNative(recipient: String, amount: BigDecimal, memo: String?): TransactionResponse {
        return payment(AssetTypeNative(), recipient, amount, memo)
    }

    suspend fun sendAsset(assetId: String, recipient: String, amount: BigDecimal, memo: String?): TransactionResponse {
        return payment(Asset.create(assetId), recipient, amount, memo)
    }

    suspend fun signedNative(recipient: String, amount: BigDecimal, memo: String?): SignedRawStellarTransaction {
        return signedTransaction(nativeOfflineOperation(recipient, amount), memo)
    }

    suspend fun signedAsset(
        assetId: String,
        recipient: String,
        amount: BigDecimal,
        memo: String?
    ): SignedRawStellarTransaction {
        return signedTransaction(paymentOperation(Asset.create(assetId), recipient, amount), memo)
    }

    suspend fun broadcastRawTransaction(
        rawTransaction: ByteArray,
        retryMetadata: RawTransactionRetryMetadata? = null,
    ): RawTransactionBroadcastResult {
        return rawTransactionBroadcaster.broadcast(rawTransaction, retryMetadata)
    }

    suspend fun transactionExists(txHash: String): Boolean {
        return rawTransactionBroadcaster.transactionExists(txHash)
    }

    suspend fun createAccount(accountId: String, startingBalance: BigDecimal, memo: String?) {
        sendTransaction(createAccountOperation(accountId, startingBalance), memo)
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

    suspend fun enableAsset(assetId: String, memo: String?) {
        changeTrust(Asset.create(assetId), memo)
    }

    fun isAssetEnabled(asset: StellarAsset.Asset) = isAssetEnabled(asset, accountId)

    fun isAssetEnabled(asset: StellarAsset.Asset, accountId: String): Boolean {
        return isAssetEnabled(server, asset, accountId)
    }

    fun getEnabledAssetsCached(): List<StellarAsset.Asset> {
        return balancesManager.getAll().map { it.asset }.filterIsInstance<StellarAsset.Asset>()
    }

    private suspend fun changeTrust(asset: Asset, memo: String?) {
        val defaultLimit = BigDecimal("922337203685.4775807") // max int64(922337203685.4775807)

        val changeTrustOperation = ChangeTrustOperation.builder()
            .asset(ChangeTrustAsset(asset))
            .limit(defaultLimit)
            .build()

        sendTransaction(changeTrustOperation, memo)
    }

    private suspend fun payment(
        asset: Asset,
        recipient: String,
        amount: BigDecimal,
        memo: String?
    ): TransactionResponse {
        return sendTransaction(paymentOperation(asset, recipient, amount), memo)
    }

    private suspend fun sendTransaction(
        operation: StellarOperation,
        memo: String?
    ): TransactionResponse {
        return submitSignedTransaction(signedTransactionInPlace(buildTransaction(operation, memo)))
    }

    private suspend fun sendTransaction(transaction: Transaction): TransactionResponse {
        return submitSignedTransaction(signedTransactionInPlace(transaction))
    }

    suspend fun sendTransaction(transactionEnvelope: String): TransactionResponse {
        val transaction = Transaction.fromEnvelopeXdr(transactionEnvelope, stellarNetwork)
        check(transaction is Transaction)

        return sendTransaction(transaction)
    }

    suspend fun signTransaction(transactionEnvelope: String): String {
        val transaction = Transaction.fromEnvelopeXdr(transactionEnvelope, stellarNetwork)
        return signedTransactionInPlace(transaction).toEnvelopeXdrBase64()
    }

    private suspend fun signedTransaction(
        operation: StellarOperation,
        memo: String?,
    ): SignedRawStellarTransaction {
        val transaction = signedTransactionInPlace(buildTransaction(operation, memo))
        return signedRawTransaction(transaction)
    }

    private suspend fun buildTransaction(
        operation: StellarOperation,
        memo: String?
    ): Transaction {
        if (!signer.canSign()) throw WalletError.WatchOnly

        val sourceAccount = horizonApi.loadAccount(accountId)
        val transactionBuilder = TransactionBuilder(sourceAccount, stellarNetwork)
            .addOperation(operation)
            .setTimeout(180)
            .setBaseFee(Transaction.MIN_BASE_FEE)

        memo?.let {
            transactionBuilder.addMemo(Memo.text(memo))
        }

        return transactionBuilder.build()
    }

    private suspend fun <T : AbstractTransaction> signedTransactionInPlace(transaction: T): T {
        if (!signer.canSign()) throw WalletError.WatchOnly

        val signature = (transaction as? Transaction)?.let { signer.signTransaction(it) }
            ?: signer.sign(transaction.hash())
        transaction.addSignature(signature)

        return transaction
    }

    private suspend fun submitSignedTransaction(transaction: Transaction): TransactionResponse {
        return horizonApi.submitTransactionXdr(transaction.toEnvelopeXdrBase64())
    }

    private fun signedRawTransaction(transaction: Transaction): SignedRawStellarTransaction {
        val raw = RawTransactionUtils.rawBytes(transaction)
        val decoded = RawTransactionUtils.decode(raw, stellarNetwork)
        val validUntil = decoded.validUntil
            ?: throw IllegalStateException("Signed Stellar transaction must have a finite timebound")

        return SignedRawStellarTransaction(
            raw = raw,
            txHash = decoded.txHash,
            sourceAccountId = decoded.sourceAccountId,
            sequenceNumber = decoded.sequenceNumber,
            validUntil = validUntil,
        )
    }

    private suspend fun nativeOfflineOperation(
        recipient: String,
        amount: BigDecimal,
    ): StellarOperation {
        return if (horizonApi.accountExists(KeyPair.fromAccountId(recipient).accountId)) {
            paymentOperation(AssetTypeNative(), recipient, amount, checkDestination = false)
        } else {
            createAccountOperation(recipient, amount)
        }
    }

    private suspend fun paymentOperation(
        asset: Asset,
        recipient: String,
        amount: BigDecimal,
        checkDestination: Boolean = true,
    ): PaymentOperation {
        val destination = KeyPair.fromAccountId(recipient)
        if (checkDestination) {
            // Avoid paying a transaction fee for a payment that Horizon already knows cannot apply.
            horizonApi.loadAccount(destination.accountId)
        }

        return PaymentOperation.builder()
            .destination(destination.accountId)
            .asset(asset)
            .amount(amount)
            .build()
    }

    private fun createAccountOperation(
        accountId: String,
        startingBalance: BigDecimal,
    ): CreateAccountOperation {
        val destination = KeyPair.fromAccountId(accountId)
        return CreateAccountOperation.builder()
            .destination(destination.accountId)
            .startingBalance(startingBalance)
            .build()
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
        @JvmOverloads
        fun getInstance(
            stellarWallet: StellarWallet,
            network: Network,
            context: Context,
            walletId: String,
            eventListenerFactory: EventListener.Factory? = null,
        ): StellarKit {
            val signer = when (stellarWallet) {
                is StellarWallet.Seed -> KeyPairSigner(KeyPair.fromBip39Seed(stellarWallet.seed, 0))
                is StellarWallet.WatchOnly -> WatchOnlySigner(KeyPair.fromAccountId(stellarWallet.addressStr).publicKey)
                is StellarWallet.SecretKey -> KeyPairSigner(KeyPair.fromSecretSeed(stellarWallet.secretSeed))
                is StellarWallet.Hardware -> throw IllegalArgumentException("Use getInstance(publicKey, signer, ...) for Hardware wallet")
            }
            val db = KitDatabase.getInstance(context, "stellar-${walletId}-${network.name}")
            return StellarKit(signer, network, db, getServer(network, eventListenerFactory))
        }

        @JvmOverloads
        fun getInstance(
            signer: Signer,
            network: Network,
            context: Context,
            walletId: String,
            eventListenerFactory: EventListener.Factory? = null,
        ): StellarKit {
            val db = KitDatabase.getInstance(context, "stellar-${walletId}-${network.name}")
            return StellarKit(signer, network, db, getServer(network, eventListenerFactory))
        }

        fun getAccountId(stellarWallet: StellarWallet): String {
            return when (stellarWallet) {
                is StellarWallet.Seed -> KeyPair.fromBip39Seed(stellarWallet.seed, 0).accountId
                is StellarWallet.WatchOnly -> KeyPair.fromAccountId(stellarWallet.addressStr).accountId
                is StellarWallet.SecretKey -> KeyPair.fromSecretSeed(stellarWallet.secretSeed).accountId
                is StellarWallet.Hardware -> KeyPair.fromPublicKey(stellarWallet.publicKey).accountId
            }
        }

        private fun getKeyPair(stellarWallet: StellarWallet): KeyPair = when (stellarWallet) {
            is StellarWallet.Seed -> KeyPair.fromBip39Seed(stellarWallet.seed, 0)
            is StellarWallet.WatchOnly -> KeyPair.fromAccountId(stellarWallet.addressStr)
            is StellarWallet.SecretKey -> KeyPair.fromSecretSeed(stellarWallet.secretSeed)
            is StellarWallet.Hardware -> throw IllegalArgumentException("Hardware wallet does not have a KeyPair")
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

        fun isAssetEnabled(
            network: Network,
            asset: StellarAsset.Asset,
            accountId: String
        ): Boolean {
            return isAssetEnabled(getServer(network), asset, accountId)
        }

        internal fun getServer(
            network: Network,
            eventListenerFactory: EventListener.Factory? = null,
        ): Server {
            val serverUrl = when (network) {
                Network.MainNet -> "https://horizon.stellar.org"
                Network.TestNet -> "https://horizon-testnet.stellar.org"
            }

            val server = Server(serverUrl)
            if (eventListenerFactory != null) {
                // Reuse the SDK's default-configured clients (timeouts, interceptors)
                // and attach only the observer. newBuilder() copies every setting, so
                // behavior is unchanged. This observes Horizon REST (balance/operation
                // sync, account lookups) and transaction-submit calls. SSE streaming
                // (UpdateManager) is intentionally NOT observed: okhttp-sse's
                // RealEventSource resets the stream call to EventListener.NONE, so no
                // per-call listener survives there. That is acceptable — the actual
                // sync and broadcast run over these REST/submit clients, so sync-time
                // network/SSL errors are still captured.
                server.httpClient = server.httpClient.newBuilder()
                    .eventListenerFactory(eventListenerFactory)
                    .build()
                server.submitHttpClient = server.submitHttpClient.newBuilder()
                    .eventListenerFactory(eventListenerFactory)
                    .build()
            }
            return server
        }

        private fun isAssetEnabled(
            server: Server,
            asset: StellarAsset.Asset,
            accountId: String
        ): Boolean {
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

sealed class EnablingAssetError : Throwable() {
    class InsufficientBalance : EnablingAssetError()
}
