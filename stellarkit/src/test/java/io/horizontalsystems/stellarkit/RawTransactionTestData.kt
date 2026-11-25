package io.horizontalsystems.stellarkit

import org.stellar.sdk.Account
import org.stellar.sdk.AssetTypeNative
import org.stellar.sdk.FeeBumpTransaction
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Transaction
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.operations.PaymentOperation
import org.stellar.sdk.responses.TransactionResponse
import java.math.BigDecimal
import org.stellar.sdk.Network as StellarSdkNetwork

internal object RawTransactionTestData {
    val network = StellarSdkNetwork.TESTNET

    fun signedPayment(
        sourceKeyPair: KeyPair = KeyPair.random(),
        destinationKeyPair: KeyPair = KeyPair.random(),
        sequenceNumber: Long = 1,
    ): Transaction {
        val source = Account(sourceKeyPair.accountId, sequenceNumber)
        val transaction = TransactionBuilder(source, network)
            .addOperation(
                PaymentOperation.builder()
                    .destination(destinationKeyPair.accountId)
                    .asset(AssetTypeNative())
                    .amount(BigDecimal.ONE)
                    .build()
            )
            .setBaseFee(Transaction.MIN_BASE_FEE)
            .setTimeout(180)
            .build()
        transaction.sign(sourceKeyPair)
        return transaction
    }

    fun unsignedPayment(sourceKeyPair: KeyPair = KeyPair.random()): Transaction {
        val source = Account(sourceKeyPair.accountId, 1)
        return TransactionBuilder(source, network)
            .addOperation(
                PaymentOperation.builder()
                    .destination(KeyPair.random().accountId)
                    .asset(AssetTypeNative())
                    .amount(BigDecimal.ONE)
                    .build()
            )
            .setBaseFee(Transaction.MIN_BASE_FEE)
            .setTimeout(180)
            .build()
    }

    fun signedFeeBump(): FeeBumpTransaction {
        val feeSource = KeyPair.random()
        return FeeBumpTransaction
            .createWithBaseFee(feeSource.accountId, Transaction.MIN_BASE_FEE, signedPayment())
            .also { it.sign(feeSource) }
    }

    fun response(txHash: String): TransactionResponse = TransactionResponse(
        "",
        "",
        true,
        txHash,
        1L,
        "",
        "",
        null,
        null,
        1L,
        "",
        null,
        null,
        100L,
        100L,
        1,
        "",
        "",
        "",
        "",
        emptyList(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
    )
}
