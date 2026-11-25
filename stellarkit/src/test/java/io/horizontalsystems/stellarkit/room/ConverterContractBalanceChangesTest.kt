package io.horizontalsystems.stellarkit.room

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.math.BigDecimal

class ConverterContractBalanceChangesTest {
    private val converter = ConverterContractBalanceChanges()

    @Test
    fun fromString_storedChanges_restoresThemWithNullSides() {
        val changes = listOf(
            Operation.ContractBalanceChange("mint", null, "GTO", StellarAsset.Asset("USDC", "GISSUER"), BigDecimal("0.1316360")),
            Operation.ContractBalanceChange("transfer", "GFROM", "CPOOL", StellarAsset.Native, BigDecimal("2")),
        )

        assertEquals(changes, converter.fromString(converter.toString(changes)))
    }

    @Test
    fun fromString_oneMalformedEntry_dropsWholeList() {
        assertNull(converter.fromString("transfer/GFROM/CPOOL/native/2;transfer/GFROM/native/2"))
    }
}
