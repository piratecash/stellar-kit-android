package io.horizontalsystems.stellartkit.sample

import android.app.Application
import co.touchlab.kermit.Logger
import io.horizontalsystems.stellarkit.Network
import io.horizontalsystems.stellarkit.StellarKit
import io.horizontalsystems.stellarkit.StellarWallet
import kotlinx.coroutines.runBlocking
import java.security.MessageDigest

class App : Application() {

    private val logger = Logger.withTag("App")

    override fun onCreate() {
        super.onCreate()
        try {
            initKit()
        } catch (e: Exception) {
            logger.e(e) { "Kit initialization failed" }
            initError = e
        }
    }

    private fun initKit() {
        val walletId = "wallet-${stellarWallet.javaClass.simpleName}"
//        val walletId = UUID.randomUUID().toString()

        val network = Network.MainNet
        // Demo only: a real wallet stores a random key in secure storage.
        val databaseKey = MessageDigest.getInstance("SHA-256").digest(walletId.toByteArray())
        migrateOrReset(network, walletId, databaseKey)
        kit = StellarKit.getInstance(
            stellarWallet,
            network,
            this,
            walletId,
            databaseKey
        )
    }

    private fun migrateOrReset(network: Network, walletId: String, databaseKey: ByteArray) {
        try {
            runBlocking { StellarKit.migrateDatabase(this@App, network, walletId, databaseKey) }
        } catch (e: Exception) {
            logger.w(e) { "Database migration failed; starting with an empty database" }
            StellarKit.clear(this, network, walletId)
        }
    }

    companion object {
        val stellarWallet = StellarWallet.WatchOnly("GADCIJ2UKQRWG6WHHPFKKLX7BYAWL7HDL54RUZO7M7UIHNQZL63C2I4Z")

        lateinit var kit: StellarKit
        var initError: Exception? = null
    }
}
