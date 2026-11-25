package io.horizontalsystems.stellarkit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.stellar.sdk.Account
import org.stellar.sdk.AssetTypeNative
import org.stellar.sdk.KeyPair
import org.stellar.sdk.TimeBounds
import org.stellar.sdk.Transaction
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.TransactionPreconditions
import org.stellar.sdk.operations.PaymentOperation
import java.math.BigDecimal
import java.math.BigInteger

class RawTransactionUtilsTest {
    @Test
    fun decode_signedEnvelope_returnsHashSourceSequenceAndValidUntil() {
        val transaction = RawTransactionTestData.signedPayment(sequenceNumber = 7)
        val raw = RawTransactionUtils.rawBytes(transaction)

        val decoded = RawTransactionUtils.decode(raw, RawTransactionTestData.network)

        assertEquals(transaction.hashHex(), decoded.txHash)
        assertEquals(transaction.sourceAccount, decoded.sourceAccountId)
        assertEquals(transaction.sequenceNumber, decoded.sequenceNumber)
        assertEquals(transaction.timeBounds.maxTime.longValueExact(), decoded.validUntil)
    }

    @Test(expected = TransactionError.InvalidRawTransaction::class)
    fun decode_emptyBytes_throwsInvalidRawTransaction() {
        RawTransactionUtils.decode(byteArrayOf(), RawTransactionTestData.network)
    }

    @Test(expected = TransactionError.InvalidRawTransaction::class)
    fun decode_malformedXdr_throwsInvalidRawTransaction() {
        RawTransactionUtils.decode(byteArrayOf(1, 2, 3, 4), RawTransactionTestData.network)
    }

    @Test(expected = TransactionError.InvalidRawTransaction::class)
    fun decode_unsignedEnvelope_throwsInvalidRawTransaction() {
        RawTransactionUtils.decode(
            RawTransactionUtils.rawBytes(RawTransactionTestData.unsignedPayment()),
            RawTransactionTestData.network
        )
    }

    @Test(expected = TransactionError.InvalidRawTransaction::class)
    fun decode_feeBumpEnvelope_throwsInvalidRawTransaction() {
        RawTransactionUtils.decode(
            RawTransactionUtils.rawBytes(RawTransactionTestData.signedFeeBump()),
            RawTransactionTestData.network
        )
    }

    @Test
    fun decode_hashMatchesSdkHashHex() {
        val transaction = RawTransactionTestData.signedPayment()

        val decoded = RawTransactionUtils.decode(
            RawTransactionUtils.rawBytes(transaction),
            RawTransactionTestData.network
        )

        assertEquals(64, decoded.txHash.length)
        assertTrue(decoded.txHash.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(transaction.hashHex(), decoded.txHash)
    }

    @Test
    fun decode_validUntil_usesUnixSeconds() {
        val transaction = RawTransactionTestData.signedPayment()

        val decoded = RawTransactionUtils.decode(
            RawTransactionUtils.rawBytes(transaction),
            RawTransactionTestData.network
        )

        val validUntil = checkNotNull(decoded.validUntil)
        val nowSeconds = System.currentTimeMillis() / 1000
        assertNotNull(validUntil)
        assertTrue(validUntil in nowSeconds..nowSeconds + 300)
    }

    @Test
    fun decode_validUntilLongMax_returnsLongMax() {
        val raw = RawTransactionUtils.rawBytes(signedPaymentWithMaxTime(BigInteger.valueOf(Long.MAX_VALUE)))

        val decoded = RawTransactionUtils.decode(raw, RawTransactionTestData.network)

        assertEquals(Long.MAX_VALUE, decoded.validUntil)
    }

    @Test(expected = TransactionError.InvalidRawTransaction::class)
    fun decode_validUntilAboveLongRange_throwsInvalidRawTransaction() {
        val raw = RawTransactionUtils.rawBytes(
            signedPaymentWithMaxTime(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE))
        )

        RawTransactionUtils.decode(raw, RawTransactionTestData.network)
    }

    private fun signedPaymentWithMaxTime(maxTime: BigInteger): Transaction {
        val sourceKeyPair = KeyPair.random()
        val transaction = TransactionBuilder(Account(sourceKeyPair.accountId, 1), RawTransactionTestData.network)
            .addOperation(
                PaymentOperation.builder()
                    .destination(KeyPair.random().accountId)
                    .asset(AssetTypeNative())
                    .amount(BigDecimal.ONE)
                    .build()
            )
            .setBaseFee(Transaction.MIN_BASE_FEE)
            .addPreconditions(
                TransactionPreconditions.builder().timeBounds(TimeBounds(BigInteger.ZERO, maxTime)).build()
            )
            .build()
        transaction.sign(sourceKeyPair)
        return transaction
    }
}
