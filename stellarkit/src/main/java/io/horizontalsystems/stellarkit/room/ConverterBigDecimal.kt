package io.horizontalsystems.stellarkit.room

import androidx.room.TypeConverter
import java.math.BigDecimal

interface Converter<T> {
    fun fromString(s: String?): T?
    fun toString(v: T?): String?
}

class ConverterBigDecimal : Converter<BigDecimal> {
    @TypeConverter
    override fun fromString(s: String?) = try {
        s?.let { BigDecimal(it) }
    } catch (e: Exception) {
        null
    }

    @TypeConverter
    override fun toString(v: BigDecimal?) = v?.toPlainString()
}

class ConverterListOfStrings : Converter<List<String>> {
    @TypeConverter
    override fun fromString(s: String?) = s?.split("|")

    @TypeConverter
    override fun toString(v: List<String>?) = v?.joinToString("|")
}

class ConverterStellarAsset : Converter<StellarAsset> {
    @TypeConverter
    override fun fromString(s: String?) = try {
        s?.let { StellarAsset.fromId(it) }
    } catch (e: Exception) {
        null
    }

    @TypeConverter
    override fun toString(v: StellarAsset?) = v?.id
}

class ConverterStellarAssetAsset : Converter<StellarAsset.Asset> {
    @TypeConverter
    override fun fromString(s: String?) = try {
        s?.let { StellarAsset.fromId(it) as? StellarAsset.Asset }
    } catch (e: Exception) {
        null
    }

    @TypeConverter
    override fun toString(v: StellarAsset.Asset?) = v?.id
}

// `type/from/to/assetId/amount` per change, `;`-joined; none of the fields can contain the separators.
class ConverterContractBalanceChanges : Converter<List<Operation.ContractBalanceChange>> {
    @TypeConverter
    override fun fromString(s: String?) = try {
        s?.split(";")?.map { entry ->
            val (type, from, to, assetId, amount) = entry.split("/").also { require(it.size == 5) }
            Operation.ContractBalanceChange(
                type = type,
                from = from.ifEmpty { null },
                to = to.ifEmpty { null },
                asset = StellarAsset.fromId(assetId),
                amount = BigDecimal(amount),
            )
        }
    } catch (e: Exception) {
        null
    }

    @TypeConverter
    override fun toString(v: List<Operation.ContractBalanceChange>?) = v?.joinToString(";") {
        listOf(it.type, it.from.orEmpty(), it.to.orEmpty(), it.asset.id, it.amount.toPlainString()).joinToString("/")
    }
}
