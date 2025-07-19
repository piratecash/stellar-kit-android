# StellarKit Android

StellarKit is a lightweight and modular client for interacting with the [Stellar](https://www.stellar.org) blockchain, written in Kotlin for Android applications. It's implemented and used by [Unstoppable Wallet](https://github.com/horizontalsystems/unstoppable-wallet-android), a multi-currency crypto wallet.

## Features

- Supports sending and receiving XLM and Stellar-based assets
- Handles account creation
- Support for mainnet/testnet networks
- Transaction building and signing
- Account balance and transaction history retrieval

## Requirements

- Android 8.0 (API level 26) or higher
- Kotlin 2.0.0+
- Java 8+

## Installation

Add the JitPack to module build.gradle
```
repositories {
    maven { url 'https://jitpack.io' }
}
```

Add the following to your `build.gradle`:
```
dependencies {
    implementation 'com.github.horizontalsystems:stellar-kit-android:<version>'
}
```

`<version>` - is the first 7 symbols of a commit hash

## Usage

Create an instance of `StellarKit` using the static method `getInstance`. It requires the following parameters:

- `stellarWallet`: an instance of `StellarWallet`, which can be one of:
  - `WatchOnly(addressStr: String)` — watch-only wallet with account address
  - `Seed(seed: ByteArray)` — wallet created from a seed byte array
  - `SecretKey(secretSeed: String)` — wallet created from a secret seed string
  - `Hardware(publicKey: ByteArray)` — hardware wallet with public key only (requires external signer)
- `network`: specify the network with the enum `Network` which supports:
  - `MainNet`
  - `TestNet`
- `context`: Android `Context` object
- `walletId`: a unique string identifier for the wallet instance, useful to distinguish multiple StellarKit instances for different accounts

```kotlin
val stellarWallet = StellarWallet.Seed(bip39Seed)
// val stellarWallet = StellarWallet.SecretKey(secretSeed)
// val stellarWallet = StellarWallet.WatchOnly(accountAddress)
// For hardware/external wallet, use the new getInstance:
// val signer = MyHardwareSigner()
// val stellarKit = StellarKit.getInstance(hardwarePublicKey, signer, Network.MainNet, context, "walletId")

// Example of a custom Signer implementation for hardware wallet
class MyHardwareSigner : Signer {
    override suspend fun sign(data: ByteArray): ByteArray {
        // Call your hardware device here
        return myHardwareSignFunction(data)
    }
}

// val signer = MyHardwareSigner()

val stellarKit = StellarKit.getInstance(stellarWallet, Network.MainNet, context, "walletId")
// For hardware wallet:
// val stellarKit = StellarKit.getInstance(stellarWallet, Network.MainNet, context, "walletId", signer = signer)
```

You can generate a BIP39 seed using a library like [hd-wallet-kit-android](https://github.com/horizontalsystems/hd-wallet-kit-android)

```kotlin
val bip39Seed = Mnemonic().toSeed(words, passphrase)
```

## Synchronization

The instance should be started to sync balances and operations. It also listens for updates. To do so you need to use the following methods:

```kotlin
// to start syncing process and to listen for updates
stellarKit.start()

// Observe syncing state
stellarKit.syncStateFlow.collect { syncState ->
    println("Sync State: $syncStateFlow")
}

// Observe syncing state of operations
stellarKit.operationsSyncStateFlow.collect { syncState ->
    println("Operations Sync State: $syncStateFlow")
}

// Refresh manually
stellarKit.refresh()

// You can stop the syncing process and updates listener by method stop
stellarKit.stop()
```

## Receive Address

```kotlin
// Get receive address
stellarKit.receiveAddress
```

## Sending Payments

```kotlin
// Send native asset
stellarKit.sendNative("recipient_account_id", BigDecimal("10.0"), "optional memo")

// If the recipient account does not exist you can create it
stellarKit.createAccount("new_account_id", BigDecimal("1.0"), "Welcome!")

// Send custom asset
stellarKit.sendAsset("ASSET_CODE:ISSUER_ACCOUNT_ID", "recipient_account_id", BigDecimal("5.0"), "optional memo")
```

## Asset Management

```kotlin
// Enable a custom asset
stellarKit.enableAsset("ASSET_CODE:ISSUER_ACCOUNT_ID", "optional memo")

// Check if asset is enabled
val isEnabled = stellarKit.isAssetEnabled(StellarAsset.Asset("ASSET_CODE", "ISSUER_ACCOUNT_ID"))
```

## Observing Balances and Updates

```kotlin
// Observe balance changes for native asset
stellarKit.getBalanceFlow(StellarAsset.Native).collect { balance ->
    println("Native balance updated: $balance")
}
```

## Example Project

All features of the library are used in the example project located in the [app](/app) folder. It can be referred to as a starting point for using the library.

## License

The `StellarKit` is open source and available under the terms of the [MIT License](https://github.com/horizontalsystems/stellar-kit-android/blob/master/LICENSE)
