package io.horizontalsystems.stellarkit.room

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.horizontalsystems.stellarkit.room.Tag.Type
import org.stellar.sdk.MemoText
import org.stellar.sdk.responses.operations.ChangeTrustOperationResponse
import org.stellar.sdk.responses.operations.CreateAccountOperationResponse
import org.stellar.sdk.responses.operations.InvokeHostFunctionOperationResponse
import org.stellar.sdk.responses.operations.OperationResponse
import org.stellar.sdk.responses.operations.PathPaymentBaseOperationResponse
import org.stellar.sdk.responses.operations.PaymentOperationResponse
import java.math.BigDecimal
import java.time.Instant

@Entity
data class Operation(
    @PrimaryKey
    val id: Long,
    val timestamp: Long,
    val pagingToken: String,
    val sourceAccount: String,
    val transactionHash: String,
    val transactionSuccessful: Boolean,
    val fee: BigDecimal?,
    val memo: String?,
    val type: String,
    @Embedded(prefix = "payment_")
    val payment: Payment?,
    @Embedded(prefix = "account_")
    val accountCreated: AccountCreated?,
    @Embedded(prefix = "trust_")
    val changeTrust: ChangeTrust?,
    @Embedded(prefix = "pathpayment_")
    val pathPayment: PathPayment? = null,
    val contractBalanceChanges: List<ContractBalanceChange>? = null,
) {
    data class Payment(val amount: BigDecimal, val asset: StellarAsset, val from: String, val to: String)
    data class AccountCreated(val startingBalance: BigDecimal, val funder: String, val account: String)
    data class ChangeTrust(
        val trustor: String,
        val trustee: String,
        val asset: StellarAsset.Asset,
        val limit: BigDecimal,
        val liquidityPoolId: String?
    )

    /** path_payment_strict_send/receive: [from] spends [sourceAsset], [to] gets [asset]. */
    data class PathPayment(
        val from: String,
        val to: String,
        val asset: StellarAsset,
        val amount: BigDecimal,
        val sourceAsset: StellarAsset,
        val sourceAmount: BigDecimal,
    )

    /** A balance movement Horizon derived from a contract call's asset events; mint has no from, burn no to. */
    data class ContractBalanceChange(
        val type: String,
        val from: String?,
        val to: String?,
        val asset: StellarAsset,
        val amount: BigDecimal,
    )

    fun contractMovement(accountId: String): ContractMovement =
        contractBalanceChanges?.let { ContractMovement.resolve(it, accountId) } ?: ContractMovement.None

    fun tags(accountId: String): List<Tag> {
        val tags = mutableListOf<Tag>()

        accountCreated?.let { accountCreated ->
            if (accountCreated.funder == accountId) {
                tags.add(Tag(id, Type.Outgoing, StellarAsset.Native.id, listOf(accountCreated.account)))
            }

            if (accountCreated.account == accountId) {
                tags.add(Tag(id, Type.Incoming, StellarAsset.Native.id, listOf(accountCreated.funder)))
            }
        }

        payment?.let { data ->
            if (data.from == accountId) {
                tags.add(Tag(id, Type.Outgoing, data.asset.id, listOf(data.to)))
            }

            if (data.to == accountId) {
                tags.add(Tag(id, Type.Incoming, data.asset.id, listOf(data.from)))
            }
        }

        changeTrust?.let { changeTrust ->
            if (changeTrust.trustee == accountId) {
                tags.add(Tag(id, Type.Outgoing, changeTrust.asset.id, listOf(changeTrust.trustor)))
            }

            if (changeTrust.trustor == accountId) {
                tags.add(Tag(id, Type.Incoming, changeTrust.asset.id, listOf(changeTrust.trustee)))
            }
        }

        pathPayment?.let { data ->
            if (data.from == accountId && data.to == accountId) {
                tags.add(Tag(id, Type.Swap, data.sourceAsset.id, listOf()))
                tags.add(Tag(id, Type.Swap, data.asset.id, listOf()))
            } else {
                if (data.from == accountId) {
                    tags.add(Tag(id, Type.Outgoing, data.sourceAsset.id, listOf(data.to)))
                }

                if (data.to == accountId) {
                    tags.add(Tag(id, Type.Incoming, data.asset.id, listOf(data.from)))
                }
            }
        }

        tags.addAll(contractMovementTags(contractMovement(accountId)))

        return tags
    }

    private fun contractMovementTags(movement: ContractMovement): List<Tag> = when (movement) {
        is ContractMovement.Swap -> listOf(
            Tag(id, Type.Swap, movement.sold.asset.id, listOf()),
            Tag(id, Type.Swap, movement.received.asset.id, listOf()),
        )
        is ContractMovement.Outgoing -> listOf(Tag(id, Type.Outgoing, movement.movement.asset.id, movement.counterparties))
        is ContractMovement.Incoming -> listOf(Tag(id, Type.Incoming, movement.movement.asset.id, movement.counterparties))
        // Still tagged per asset, so the call stays visible in each moved coin's history.
        is ContractMovement.Unrepresentable ->
            movement.sold.map { Tag(id, Type.Outgoing, it.asset.id, listOf()) } +
                movement.received.map { Tag(id, Type.Incoming, it.asset.id, listOf()) }
        ContractMovement.None -> listOf()
    }

    companion object {
        fun fromApi(operationResponse: OperationResponse): Operation {
            var payment: Payment? = null
            var accountCreated: AccountCreated? = null
            var changeTrust: ChangeTrust? = null
            var pathPayment: PathPayment? = null
            var contractBalanceChanges: List<ContractBalanceChange>? = null

            when (operationResponse) {
                is PaymentOperationResponse -> {
                    payment = Payment(
                        amount = operationResponse.amount.toBigDecimal(),
                        asset = StellarAsset.fromSdkModel(operationResponse.asset),
                        from = operationResponse.from,
                        to = operationResponse.to,
                    )
                }
                is PathPaymentBaseOperationResponse -> {
                    pathPayment = PathPayment(
                        from = operationResponse.from,
                        to = operationResponse.to,
                        asset = StellarAsset.fromSdkModel(operationResponse.asset),
                        amount = operationResponse.amount.toBigDecimal(),
                        sourceAsset = StellarAsset.fromSdkModel(operationResponse.sourceAsset),
                        sourceAmount = operationResponse.sourceAmount.toBigDecimal(),
                    )
                }
                is InvokeHostFunctionOperationResponse -> {
                    contractBalanceChanges = contractBalanceChanges(operationResponse)
                }
                is CreateAccountOperationResponse -> {
                    accountCreated = AccountCreated(
                        startingBalance = operationResponse.startingBalance.toBigDecimal(),
                        funder = operationResponse.funder,
                        account = operationResponse.account,
                    )
                }
                is ChangeTrustOperationResponse -> {
                    changeTrust = ChangeTrust(
                        trustor = operationResponse.trustor,
                        trustee = operationResponse.trustee,
                        asset = StellarAsset.Asset(operationResponse.assetCode, operationResponse.assetIssuer),
                        limit = operationResponse.limit.toBigDecimal(),
                        liquidityPoolId = operationResponse.liquidityPoolId,
                    )
                }
            }

            return Operation(
                id = operationResponse.id,
                timestamp = Instant.parse(operationResponse.createdAt).epochSecond,
                pagingToken = operationResponse.pagingToken,
                sourceAccount = operationResponse.sourceAccount,
                transactionHash = operationResponse.transactionHash,
                transactionSuccessful = operationResponse.transactionSuccessful,
                fee = operationResponse.transaction?.feeCharged?.let {
                    BigDecimal(it).movePointLeft(7).stripTrailingZeros()
                },
                memo = (operationResponse.transaction?.memo as? MemoText)?.text,
                type = operationResponse.type,
                payment = payment,
                accountCreated = accountCreated,
                changeTrust = changeTrust,
                pathPayment = pathPayment,
                contractBalanceChanges = contractBalanceChanges,
            )
        }

        // All or nothing: a partly parsed list would misstate the movement, and a throw here would stall the sync.
        private fun contractBalanceChanges(response: InvokeHostFunctionOperationResponse): List<ContractBalanceChange>? =
            try {
                response.assetBalanceChanges?.map { change ->
                    ContractBalanceChange(
                        type = change.type,
                        from = change.from,
                        to = change.to,
                        asset = StellarAsset.fromSdkModel(change.asset),
                        amount = change.amount.toBigDecimal(),
                    )
                }?.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                null
            }
    }
}

data class OperationInfo(
    val operations: List<Operation>,
    val initial: Boolean,
)

@Entity
data class OperationSyncState(
    @PrimaryKey
    val id: String,
    val allSynced: Boolean,
) {
    constructor(allSynced: Boolean) : this("unique_id", allSynced)
}
