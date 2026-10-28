package io.horizontalsystems.stellarkit

import co.touchlab.kermit.Logger
import io.horizontalsystems.stellarkit.room.AssetBalance
import io.horizontalsystems.stellarkit.room.BalanceDao
import io.horizontalsystems.stellarkit.room.StellarAsset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.stellar.sdk.Server
import org.stellar.sdk.exception.BadRequestException
import java.math.BigDecimal

class BalancesManager(
    private val server: Server,
    private val balanceDao: BalanceDao,
    private val accountId: String,
    private val logger: Logger,
) {
    companion object {
        val baseReserve = BigDecimal("0.5")
    }

    private val _syncStateFlow = MutableStateFlow<SyncState>(SyncState.NotSynced(StellarKit.SyncError.NotStarted))
    val syncStateFlow = _syncStateFlow.asStateFlow()

    val assetBalanceMapFlow = balanceDao.getAssetBalancesFlow().map {
        it.map { it.asset to it }.toMap()
    }

    fun getBalanceFlow(asset: StellarAsset): Flow<AssetBalance?> {
        return assetBalanceMapFlow.map { it[asset] }.distinctUntilChanged()
    }

    suspend fun sync() {
        logger.d { "Syncing balances..." }

        if (_syncStateFlow.value is SyncState.Syncing) {
            logger.d { "Syncing balances is in progress" }
            return
        }

        _syncStateFlow.update {
            SyncState.Syncing
        }

        try {
            val accounts = server.accounts()
            val account = accounts.account(accountId)

            val assetBalances = mutableListOf<AssetBalance>()

            val minXlmBalance = BigDecimal(2 + account.subentryCount) * baseReserve

            account.balances.forEach { balance ->
                val asset = if (balance.assetType == "native") {
                    StellarAsset.Native
                } else {
                    StellarAsset.Asset(balance.assetCode, balance.assetIssuer)
                }

                val minBalance = if (asset == StellarAsset.Native) {
                    minXlmBalance
                } else {
                    BigDecimal.ZERO
                }

                assetBalances.add(
                    AssetBalance(
                        asset = asset,
                        balance = balance.balance.toBigDecimal(),
                        minBalance = minBalance,
                    )
                )
            }

            balanceDao.deleteAllAndInsertNew(assetBalances)

            _syncStateFlow.update {
                SyncState.Synced
            }

        } catch (e: Throwable) {
            if (e is BadRequestException && e.code == 404) {
                _syncStateFlow.update {
                    SyncState.Synced
                }
            } else {
                logger.e { "error on BalancesManager::sync() $e" }
                _syncStateFlow.update {
                    SyncState.NotSynced(e)
                }
            }
        }
    }

    suspend fun getBalance(asset: StellarAsset): AssetBalance? {
        return balanceDao.getBalance(asset)
    }

    suspend fun getAll(): List<AssetBalance> {
        return balanceDao.getAll()
    }
}
