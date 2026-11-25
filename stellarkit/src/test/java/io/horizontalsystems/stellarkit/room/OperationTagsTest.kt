package io.horizontalsystems.stellarkit.room

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class OperationTagsTest {

    @Test
    fun tags_pathPaymentToAccount_isIncomingOfDeliveredAsset() {
        val tags = operation(pathPayment = pathPayment(from = OTHER, to = ACCOUNT)).tags(ACCOUNT)

        assertEquals(listOf(Triple(Tag.Type.Incoming, USDC.id, listOf(OTHER))), tags.summary())
    }

    @Test
    fun tags_pathPaymentFromAccount_isOutgoingOfSpentAsset() {
        val tags = operation(pathPayment = pathPayment(from = ACCOUNT, to = OTHER)).tags(ACCOUNT)

        assertEquals(listOf(Triple(Tag.Type.Outgoing, StellarAsset.Native.id, listOf(OTHER))), tags.summary())
    }

    @Test
    fun tags_pathPaymentToSelf_isSwapOfBothAssets() {
        val tags = operation(pathPayment = pathPayment(from = ACCOUNT, to = ACCOUNT)).tags(ACCOUNT)

        assertEquals(
            listOf(Triple(Tag.Type.Swap, StellarAsset.Native.id, emptyList<String>()), Triple(Tag.Type.Swap, USDC.id, emptyList<String>())),
            tags.summary()
        )
    }

    @Test
    fun tags_contractRefundOfSoldAsset_isOutgoingOnly() {
        val tags = operation(
            changes = listOf(change(ACCOUNT, POOL, StellarAsset.Native, "5"), change(POOL, ACCOUNT, StellarAsset.Native, "1"))
        ).tags(ACCOUNT)

        assertEquals(listOf(Triple(Tag.Type.Outgoing, StellarAsset.Native.id, listOf(POOL))), tags.summary())
    }

    @Test
    fun tags_contractSwap_isSwapOfBothAssets() {
        val tags = operation(
            changes = listOf(change(ACCOUNT, POOL, StellarAsset.Native, "5"), change(POOL, ACCOUNT, USDC, "1"))
        ).tags(ACCOUNT)

        assertEquals(
            listOf(Triple(Tag.Type.Swap, StellarAsset.Native.id, emptyList<String>()), Triple(Tag.Type.Swap, USDC.id, emptyList<String>())),
            tags.summary()
        )
    }

    @Test
    fun tags_contractUnrepresentable_tagsEveryMovedAsset() {
        val tags = operation(
            changes = listOf(change(ACCOUNT, POOL, StellarAsset.Native, "5"), change(ACCOUNT, POOL, USDC, "1"))
        ).tags(ACCOUNT)

        assertEquals(
            listOf(Triple(Tag.Type.Outgoing, StellarAsset.Native.id, emptyList<String>()), Triple(Tag.Type.Outgoing, USDC.id, emptyList<String>())),
            tags.summary()
        )
    }

    @Test
    fun tags_operationWithoutPayload_hasNoTags() {
        assertEquals(listOf<Tag>(), operation().tags(ACCOUNT))
    }

    private fun List<Tag>.summary() = map { Triple(it.type, it.assetId, it.accountIds) }

    private companion object {
        const val ACCOUNT = "GACCOUNT"
        const val OTHER = "GOTHER"
        const val POOL = "CPOOL"
        val USDC = StellarAsset.Asset("USDC", "GISSUER")

        fun pathPayment(from: String, to: String) = Operation.PathPayment(
            from = from,
            to = to,
            asset = USDC,
            amount = BigDecimal("0.86"),
            sourceAsset = StellarAsset.Native,
            sourceAmount = BigDecimal("5"),
        )

        fun change(from: String?, to: String?, asset: StellarAsset, amount: String) =
            Operation.ContractBalanceChange("transfer", from, to, asset, BigDecimal(amount))

        fun operation(
            pathPayment: Operation.PathPayment? = null,
            changes: List<Operation.ContractBalanceChange>? = null,
        ) = Operation(
            id = 1,
            timestamp = 0,
            pagingToken = "1",
            sourceAccount = ACCOUNT,
            transactionHash = "hash",
            transactionSuccessful = true,
            fee = null,
            memo = null,
            type = "test",
            payment = null,
            accountCreated = null,
            changeTrust = null,
            pathPayment = pathPayment,
            contractBalanceChanges = changes,
        )
    }
}
