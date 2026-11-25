package io.horizontalsystems.stellarkit.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.stellar.sdk.Server
import java.math.BigDecimal

// Samples are real Horizon operation records; the contract calls are trimmed to the parsed fields.
class OperationFromApiTest {

    @Test
    fun fromApi_pathPaymentStrictSend_parsesBothSides() {
        val operation = operation("path_payment_strict_send_other.json")

        assertEquals(
            Operation.PathPayment(
                from = "GDJ3KNAXKILI62M2TWMKVTZ6OSS3GRV2DTNGXV6BF6OWF2OXKQGQJYNE",
                to = "GA55YKHIIYGW6L4GVU4URRMZGHPPYICSCD3KD45QOPV25Z2KZM6O35NQ",
                asset = StellarAsset.Asset("USDX", "GA5G7HKQBJ4PDKIVE53FOZPY3C7YZI5R4QTDQN3UKILQYME7KUL2CKKH"),
                amount = BigDecimal("7446.5025578"),
                sourceAsset = StellarAsset.Asset("XPi", "GDOFJEZ3FUWAC2EVHPRMJPSWPOD3KJF7FTXFZIERFFPB2VMTRQLZFUEF"),
                sourceAmount = BigDecimal("11.0000000"),
            ),
            operation.pathPayment
        )
    }

    @Test
    fun fromApi_pathPaymentStrictReceiveToSelf_parsesNativeSource() {
        val pathPayment = operation("path_payment_strict_receive_self.json").pathPayment

        assertEquals(pathPayment?.from, pathPayment?.to)
        assertEquals(StellarAsset.Native, pathPayment?.sourceAsset)
        assertEquals(BigDecimal("3.0000000"), pathPayment?.sourceAmount)
    }

    @Test
    fun fromApi_invokeHostFunction_parsesBalanceChanges() {
        val operation = operation("invoke_host_function_swap.json")
        val account = "GDQJ6ZIOEFMEB7P3FXOZ4OWJ2HLACVBHLW2DCJGVXEW56JNXJY3BEOHU"
        val aqua = StellarAsset.Asset("AQUA", "GBNZILSTVQZ4R7IKQDGHYGY2QXL5QOFJYQMXPKWRRM5PAV7Y4M67AQUA")

        assertEquals(
            ContractMovement.Swap(
                ContractMovement.Movement(aqua, BigDecimal("-1642.5644117")),
                ContractMovement.Movement(StellarAsset.Native, BigDecimal("2.6986611")),
            ),
            operation.contractMovement(account)
        )
    }

    @Test
    fun fromApi_invokeHostFunctionWithUnmappableAsset_dropsAllChanges() {
        assertNull(operation("invoke_host_function_unknown_asset.json").contractBalanceChanges)
    }

    // Through the SDK's own page parsing, the same path OperationManager uses.
    private fun operation(resource: String): Operation {
        val record = requireNotNull(javaClass.classLoader?.getResource("horizon/$resource")).readText()
        return MockWebServer().use { horizon ->
            horizon.enqueue(MockResponse().setBody("""{"_embedded":{"records":[$record]}}"""))
            val response = Server(horizon.url("/").toString()).operations().execute().records.single()
            Operation.fromApi(response)
        }
    }
}
