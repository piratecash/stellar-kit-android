package io.horizontalsystems.stellarkit.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.stellarkit.TagQuery
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.math.BigDecimal

@RunWith(RobolectricTestRunner::class)
class OperationDaoTransactionTest {
    private lateinit var database: KitDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KitDatabase::class.java
        ).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun saveWithTags_tagInsertFails_keepsPreviousOperationsAndTags() = runTest {
        val dao = database.operationDao()
        dao.saveWithTags(listOf(operation(1)), listOf(tag(1)))
        failInsertsInto("Tag")

        val result = runCatching { dao.saveWithTags(listOf(operation(2)), listOf(tag(2))) }

        assertTrue(result.isFailure)
        assertEquals(listOf(1L), dao.operationsBefore(TagQuery(null, null, null), Long.MAX_VALUE, 10).map { it.id })
        assertEquals(listOf(1L), dao.operationsBefore(incomingNative, Long.MAX_VALUE, 10).map { it.id })
    }

    @Test
    fun deleteAllAndInsertNew_insertFails_keepsPreviousBalances() = runTest {
        val dao = database.balanceDao()
        val previous = AssetBalance(StellarAsset.Native, BigDecimal.TEN, BigDecimal.ONE)
        dao.deleteAllAndInsertNew(listOf(previous))
        failInsertsInto("AssetBalance")

        val result = runCatching {
            dao.deleteAllAndInsertNew(listOf(previous.copy(balance = BigDecimal.ONE)))
        }

        assertTrue(result.isFailure)
        assertEquals(listOf(previous), dao.getAll())
    }

    private fun failInsertsInto(table: String) {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER fail_$table BEFORE INSERT ON `$table` BEGIN SELECT RAISE(ABORT, 'injected'); END"
        )
    }

    private fun operation(id: Long) = Operation(
        id = id,
        timestamp = id,
        pagingToken = id.toString(),
        sourceAccount = "source",
        transactionHash = "hash-$id",
        transactionSuccessful = true,
        fee = BigDecimal.ONE,
        memo = null,
        type = "create_account",
        payment = null,
        accountCreated = Operation.AccountCreated(BigDecimal.TEN, "funder", "account"),
        changeTrust = null,
    )

    private fun tag(operationId: Long) =
        Tag(operationId, Tag.Type.Incoming, StellarAsset.Native.id, listOf("funder"))

    private val incomingNative = TagQuery(Tag.Type.Incoming, StellarAsset.Native.id, null)
}
