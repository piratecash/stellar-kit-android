package io.horizontalsystems.stellarkit.room

import java.math.BigDecimal

/** The account's net asset movements of one contract call: sold amounts are negative, received positive. */
sealed class ContractMovement {
    data class Movement(val asset: StellarAsset, val amount: BigDecimal)

    data class Swap(val sold: Movement, val received: Movement) : ContractMovement()
    data class Outgoing(val movement: Movement, val counterparties: List<String>) : ContractMovement()
    data class Incoming(val movement: Movement, val counterparties: List<String>) : ContractMovement()

    /** More than one asset moved in one direction: no single pair states it truthfully. */
    data class Unrepresentable(val sold: List<Movement>, val received: List<Movement>) : ContractMovement()

    data object None : ContractMovement()

    companion object {
        fun resolve(changes: List<Operation.ContractBalanceChange>, accountId: String): ContractMovement {
            val net = mutableMapOf<StellarAsset, BigDecimal>()
            changes.forEach { change ->
                if (change.from == accountId) net.merge(change.asset, change.amount.negate(), BigDecimal::add)
                if (change.to == accountId) net.merge(change.asset, change.amount, BigDecimal::add)
            }

            val sold = net.filterValues { it.signum() < 0 }.map { Movement(it.key, it.value) }
            val received = net.filterValues { it.signum() > 0 }.map { Movement(it.key, it.value) }

            return when {
                sold.isEmpty() && received.isEmpty() -> None
                sold.size == 1 && received.size == 1 -> Swap(sold.single(), received.single())
                sold.size == 1 && received.isEmpty() ->
                    Outgoing(sold.single(), counterparties(changes, sold.single().asset, accountId, outgoing = true))
                received.size == 1 && sold.isEmpty() ->
                    Incoming(received.single(), counterparties(changes, received.single().asset, accountId, outgoing = false))
                else -> Unrepresentable(sold, received)
            }
        }

        // A mint has no sender and a burn no recipient: on Stellar that side is the asset issuer.
        private fun counterparties(
            changes: List<Operation.ContractBalanceChange>,
            asset: StellarAsset,
            accountId: String,
            outgoing: Boolean,
        ): List<String> = changes
            .filter { it.asset == asset && it.from != it.to }
            .filter { if (outgoing) it.from == accountId else it.to == accountId }
            .mapNotNull { (if (outgoing) it.to else it.from) ?: (asset as? StellarAsset.Asset)?.issuer }
            .distinct()
    }
}
