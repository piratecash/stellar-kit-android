package io.horizontalsystems.stellarkit

sealed interface StellarWallet {
    data class WatchOnly(val addressStr: String) : StellarWallet
    data class Seed(val seed: ByteArray) : StellarWallet
    data class SecretKey(val secretSeed: String) : StellarWallet
    data class Hardware(val publicKey: ByteArray) : StellarWallet
}