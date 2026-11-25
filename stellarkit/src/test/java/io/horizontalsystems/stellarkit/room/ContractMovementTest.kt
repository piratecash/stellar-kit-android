package io.horizontalsystems.stellarkit.room

import org.junit.Assert.assertEquals
import org.junit.Test
import java.math.BigDecimal

class ContractMovementTest {

    @Test
    fun resolve_soldOneReceivedAnother_isSwap() {
        val movement = resolve(change(ACCOUNT, POOL, StellarAsset.Native, "5"), change(POOL, ACCOUNT, USDC, "0.86"))

        assertEquals(ContractMovement.Swap(movement(StellarAsset.Native, "-5"), movement(USDC, "0.86")), movement)
    }

    @Test
    fun resolve_repeatedMovementsOfOneAsset_areSummed() {
        val movement = resolve(
            change(ACCOUNT, POOL, StellarAsset.Native, "3"),
            change(ACCOUNT, POOL, StellarAsset.Native, "2"),
            change(POOL, ACCOUNT, USDC, "0.86"),
        )

        assertEquals(BigDecimal("-5"), (movement as ContractMovement.Swap).sold.amount)
    }

    @Test
    fun resolve_refundOfSoldAsset_isNettedToOutgoing() {
        val movement = resolve(change(ACCOUNT, POOL, StellarAsset.Native, "5"), change(POOL, ACCOUNT, StellarAsset.Native, "1"))

        assertEquals(ContractMovement.Outgoing(movement(StellarAsset.Native, "-4"), listOf(counterparty(POOL, "5"))), movement)
    }

    @Test
    fun resolve_oneAssetToTwoRecipients_keepsBothCounterparties() {
        val movement = resolve(change(ACCOUNT, POOL, StellarAsset.Native, "3"), change(ACCOUNT, OTHER, StellarAsset.Native, "7"))

        assertEquals(
            ContractMovement.Outgoing(
                movement(StellarAsset.Native, "-10"),
                listOf(counterparty(POOL, "3"), counterparty(OTHER, "7"))
            ),
            movement
        )
    }

    @Test
    fun resolve_twoSendersOfOneAsset_keepsEachSenderAmount() {
        val movement = resolve(
            change(POOL, ACCOUNT, USDC, "1"),
            change(OTHER, ACCOUNT, USDC, "2"),
            change(POOL, ACCOUNT, USDC, "4"),
        )

        assertEquals(
            ContractMovement.Incoming(movement(USDC, "7"), listOf(counterparty(POOL, "5"), counterparty(OTHER, "2"))),
            movement
        )
    }

    @Test
    fun resolve_mintsToAccount_isIncomingFromIssuer() {
        val movement = resolve(mint(USDC, "0.1"), mint(USDC, "0.2"))

        assertEquals(ContractMovement.Incoming(movement(USDC, "0.3"), listOf(counterparty(ISSUER, "0.3"))), movement)
    }

    @Test
    fun resolve_selfTransferAndRoundTrip_isNone() {
        val movement = resolve(
            change(ACCOUNT, POOL, USDC, "1"),
            change(ACCOUNT, ACCOUNT, StellarAsset.Native, "2"),
            change(POOL, ACCOUNT, USDC, "1"),
        )

        assertEquals(ContractMovement.None, movement)
    }

    @Test
    fun resolve_twoAssetsSold_isUnrepresentable() {
        val movement = resolve(change(ACCOUNT, POOL, StellarAsset.Native, "5"), change(ACCOUNT, POOL, USDC, "1"))

        assertEquals(
            ContractMovement.Unrepresentable(listOf(movement(StellarAsset.Native, "-5"), movement(USDC, "-1")), listOf()),
            movement
        )
    }

    @Test
    fun resolve_changesOfOtherAccounts_isNone() {
        assertEquals(ContractMovement.None, resolve(change(POOL, OTHER, StellarAsset.Native, "5")))
    }

    private fun resolve(vararg changes: Operation.ContractBalanceChange) = ContractMovement.resolve(changes.toList(), ACCOUNT)

    private fun movement(asset: StellarAsset, amount: String) = ContractMovement.Movement(asset, BigDecimal(amount))

    private fun counterparty(address: String, amount: String) = ContractMovement.Counterparty(address, BigDecimal(amount))

    private companion object {
        const val ACCOUNT = "GACCOUNT"
        const val POOL = "CPOOL"
        const val OTHER = "GOTHER"
        const val ISSUER = "GISSUER"
        val USDC = StellarAsset.Asset("USDC", ISSUER)

        fun change(from: String?, to: String?, asset: StellarAsset, amount: String) =
            Operation.ContractBalanceChange("transfer", from, to, asset, BigDecimal(amount))

        fun mint(asset: StellarAsset, amount: String) =
            Operation.ContractBalanceChange("mint", null, ACCOUNT, asset, BigDecimal(amount))
    }
}
