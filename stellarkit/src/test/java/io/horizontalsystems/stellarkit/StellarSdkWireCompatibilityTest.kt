package io.horizontalsystems.stellarkit

import org.junit.Assert.assertEquals
import org.junit.Test
import org.stellar.sdk.Account
import org.stellar.sdk.Asset
import org.stellar.sdk.AssetTypeNative
import org.stellar.sdk.KeyPair
import org.stellar.sdk.Memo
import org.stellar.sdk.TimeBounds
import org.stellar.sdk.Transaction
import org.stellar.sdk.TransactionBuilder
import org.stellar.sdk.TransactionPreconditions
import org.stellar.sdk.operations.PaymentOperation
import java.math.BigDecimal
import java.math.BigInteger
import org.stellar.sdk.Network as StellarSdkNetwork

// Golden envelopes were captured on stellar-sdk 1.0.0; any byte change means signing/serialization changed.
class StellarSdkWireCompatibilityTest {
    @Test
    fun signedNativePayment_fixedInputs_matchesGoldenEnvelope() {
        val envelope = buildSignedPaymentEnvelope(AssetTypeNative())

        assertEquals(readGolden("native_payment.txt"), envelope)
    }

    @Test
    fun signedAssetPayment_fixedInputs_matchesGoldenEnvelope() {
        val envelope = buildSignedPaymentEnvelope(Asset.create("USDC:$ISSUER_ACCOUNT_ID"))

        assertEquals(readGolden("asset_payment.txt"), envelope)
    }

    private fun buildSignedPaymentEnvelope(asset: Asset): String {
        val sourceKeyPair = KeyPair.fromSecretSeed(SOURCE_SECRET_SEED)
        val account = Account(sourceKeyPair.accountId, SEQUENCE_NUMBER)

        val transaction = TransactionBuilder(account, StellarSdkNetwork.TESTNET)
            .addOperation(
                PaymentOperation.builder()
                    .destination(DESTINATION_ACCOUNT_ID)
                    .asset(asset)
                    .amount(BigDecimal.TEN)
                    .build()
            )
            .addPreconditions(preconditions())
            .addMemo(Memo.text(MEMO_TEXT))
            .setBaseFee(BASE_FEE)
            .build()
        transaction.sign(sourceKeyPair)

        return transaction.toEnvelopeXdrBase64()
    }

    private fun preconditions(): TransactionPreconditions =
        TransactionPreconditions.builder()
            .timeBounds(TimeBounds(MIN_TIME, MAX_TIME))
            .build()

    private fun readGolden(name: String): String {
        val stream = checkNotNull(javaClass.getResourceAsStream("/xdr/$name")) {
            "Missing golden resource /xdr/$name"
        }
        return stream.use { String(it.readBytes(), Charsets.US_ASCII).trim() }
    }

    private companion object {
        const val SOURCE_SECRET_SEED = "SADUSEZMPMOWIIP4AGDXRPZFFC6QH2KE23OF7WZOGILC2RMWL6XYE2XN"
        const val DESTINATION_ACCOUNT_ID = "GDZ6W7II4R4RAATZQXROAHMFMTXYSHMTDZIYUAOKFHPQ535DWHWTED3I"
        const val ISSUER_ACCOUNT_ID = "GDP3DR7DPVFWRN6SH4SPIYKPQH6YE3CJF4LDKGVJSHRKHNPZSC7O52Y5"
        const val SEQUENCE_NUMBER = 11L
        const val BASE_FEE = 100L
        const val MEMO_TEXT = "wire-golden"
        val MIN_TIME: BigInteger = BigInteger.ZERO
        val MAX_TIME: BigInteger = BigInteger.valueOf(4_102_444_800L)
    }
}
