package io.horizontalsystems.stellarkit

sealed class TransactionError : Throwable() {
    class InvalidRawTransaction(override val message: String) : TransactionError()
    data object RawTransactionExpired : TransactionError() {
        override val message = "Raw transaction expired"
    }
    data object RetryMetadataMismatch : TransactionError() {
        override val message = "Raw transaction retry metadata does not match transaction"
    }
    data object BroadcastAlreadyInProgress : TransactionError() {
        override val message = "Raw transaction broadcast already in progress"
    }
}
