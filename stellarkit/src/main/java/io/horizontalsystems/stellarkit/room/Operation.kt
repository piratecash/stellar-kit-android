package io.horizontalsystems.stellarkit.room

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.PrimaryKey
import io.horizontalsystems.stellarkit.room.Tag.Type
import org.stellar.sdk.MemoText
import org.stellar.sdk.responses.operations.ChangeTrustOperationResponse
import org.stellar.sdk.responses.operations.CreateAccountOperationResponse
import org.stellar.sdk.responses.operations.OperationResponse
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

        return tags
    }

    companion object {
        fun fromApi(operationResponse: OperationResponse): Operation {
            var payment: Payment? = null
            var accountCreated: AccountCreated? = null
            var changeTrust: ChangeTrust? = null

            when (operationResponse) {
                is PaymentOperationResponse -> {
                    payment = Payment(
                        amount = operationResponse.amount.toBigDecimal(),
                        asset = StellarAsset.fromSdkModel(operationResponse.asset),
                        from = operationResponse.from,
                        to = operationResponse.to,
                    )
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
            )
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
