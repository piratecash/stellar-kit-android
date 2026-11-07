package io.horizontalsystems.stellarkit.room

import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.stellarkit.Network
import io.horizontalsystems.stellarkit.PlatformContext
import io.horizontalsystems.stellarkit.StellarKit
import io.horizontalsystems.stellarkit.StellarWallet
import io.horizontalsystems.stellarkit.TagQuery
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.stellar.sdk.KeyPair
import java.io.File

// Android keeps the default names forever: a change here makes every existing wallet resync from scratch.
class StellarKitDatabaseNameResolverDesktopTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val key = ByteArray(32) { it.toByte() }
    private val directory: File get() = tmp.root
    private val context: PlatformContext get() = PlatformContext(directory)
    private val opaque = DatabaseNameResolver { "n${it.hashCode().toUInt()}" }

    @Test
    fun getInstance_defaultResolver_opensLiteralName() {
        kit(WALLET, Network.MainNet)

        assertTrue(File(directory, "stellar-w1-MainNet").isFile)
    }

    @Test
    fun getInstance_differentNetworksAndWallets_namesArePairwiseDistinct() {
        val defaults = listOf("stellar-w1-MainNet", "stellar-w1-TestNet", "stellar-w2-MainNet")
        kit(WALLET, Network.MainNet)
        kit(WALLET, Network.TestNet)
        kit("w2", Network.MainNet)
        kit(WALLET, Network.MainNet, opaque)
        kit(WALLET, Network.TestNet, opaque)
        kit("w2", Network.MainNet, opaque)

        assertEquals((defaults + defaults.map(opaque::resolve)).toSet(), databaseFiles())
    }

    @Test
    fun getInstance_overrideResolver_leavesNoDefaultNameOnDisk() {
        kit(WALLET, Network.MainNet, opaque)

        assertEquals(setOf(opaque.resolve("stellar-w1-MainNet")), databaseFiles())
    }

    @Test
    fun migrateDatabase_overrideResolver_namespaceFilesUseResolvedName() = runBlocking<Unit> {
        StellarKit.migrateDatabase(context, Network.MainNet, WALLET, key, opaque)

        assertEquals(listOf(".${opaque.resolve("stellar-kit")}-sqlcipher.lock"), directory.list().orEmpty().toList())
    }

    @Test
    fun clear_defaultResolver_removesLiteralName() {
        val file = File(directory, "stellar-w1-MainNet").apply { writeText("x") }

        StellarKit.clear(context, Network.MainNet, WALLET)

        assertFalse(file.exists())
    }

    @Test
    fun clear_overrideResolver_removesResolvedNameAndKeepsDefaultOne() {
        File(directory, opaque.resolve("stellar-w1-MainNet")).writeText("x")
        File(directory, "stellar-w1-MainNet").writeText("x")

        StellarKit.clear(context, Network.MainNet, WALLET, opaque)

        assertEquals(setOf("stellar-w1-MainNet"), databaseFiles())
    }

    @Test
    fun migrateDatabase_resolvedNameInResolvedNamespacePrefix_throws() {
        val reserved = DatabaseNameResolver { if (it == "stellar-kit") "ns" else ".ns-sqlcipher-x" }

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { StellarKit.migrateDatabase(context, Network.MainNet, WALLET, key, reserved) }
        }
    }

    private fun kit(
        walletId: String,
        network: Network,
        nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
    ): StellarKit = StellarKit.getInstance(
        StellarWallet.WatchOnly(KeyPair.random().accountId),
        network,
        context,
        walletId,
        key,
        nameResolver = nameResolver,
    ).also { kit -> runBlocking { kit.operationsBefore(TagQuery(null, null, null)) } }

    private fun databaseFiles(): Set<String> = directory.list().orEmpty()
        .filterNot { it.startsWith(".") || it.endsWith(".lck") || it.endsWith("-wal") || it.endsWith("-shm") }.toSet()

    private companion object {
        const val WALLET = "w1"
    }
}
