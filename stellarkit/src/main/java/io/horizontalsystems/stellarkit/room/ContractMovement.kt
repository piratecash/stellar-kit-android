package io.horizontalsystems.stellarkit.room

import java.math.BigDecimal

/** The account's net asset movements of one contract call: sold amounts are negative, received positive. */
sealed class ContractMovement {
    data class Movement(val asset: StellarAsset, val amount: BigDecimal)

    /** [amount] is the gross amount this counterparty received from or sent to the account. */
    data class Counterparty(val address: String, val amount: BigDecimal)

    data class Swap(val sold: Movement, val received: Movement) : ContractMovement()
    data class Outgoing(val movement: Movement, val counterparties: List<Counterparty>) : ContractMovement()
    data class Incoming(val movement: Movement, val counterparties: List<Counterparty>) : ContractMovement()

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
        ): List<Counterparty> {
            val amounts = linkedMapOf<String, BigDecimal>()
            changes
                .filter { it.asset == asset && it.from != it.to }
                .filter { if (outgoing) it.from == accountId else it.to == accountId }
                .forEach { change ->
                    val address = (if (outgoing) change.to else change.from) ?: (asset as? StellarAsset.Asset)?.issuer
                    address?.let { amounts.merge(it, change.amount, BigDecimal::add) }
                }
            return amounts.map { (address, amount) -> Counterparty(address, amount) }
        }
    }
}
