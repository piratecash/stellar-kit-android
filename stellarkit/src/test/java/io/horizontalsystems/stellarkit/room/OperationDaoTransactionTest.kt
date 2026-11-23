package io.horizontalsystems.stellarkit.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import io.horizontalsystems.stellarkit.TagQuery
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun operationsBefore_sinceTimestamp_includesBoundary() = runTest {
        saveOperations(1, 2, 3, 4)

        assertEquals(listOf(4L, 3L, 2L), ids(sinceTimestamp = 2))
    }

    @Test
    fun operationsBefore_untilTimestamp_excludesBoundary() = runTest {
        saveOperations(1, 2, 3, 4)

        assertEquals(listOf(2L, 1L), ids(untilTimestamp = 3))
    }

    @Test
    fun operationsBefore_sinceAndUntil_returnsHalfOpenPeriod() = runTest {
        saveOperations(1, 2, 3, 4)

        assertEquals(listOf(3L, 2L), ids(sinceTimestamp = 2, untilTimestamp = 4))
    }

    @Test
    fun operationsBefore_cursorAndPeriod_appliesBoth() = runTest {
        saveOperations(1, 2, 3, 4, 5)

        assertEquals(listOf(3L, 2L), ids(fromId = 4, sinceTimestamp = 2, untilTimestamp = 5))
    }

    @Test
    fun operationsBefore_periodWithoutMatches_returnsEmpty() = runTest {
        saveOperations(1, 2)

        assertTrue(ids(sinceTimestamp = 10, untilTimestamp = 20).isEmpty())
    }

    @Test
    fun operationsBefore_tagFilterAndPeriod_appliesBoth() = runTest {
        val dao = database.operationDao()
        dao.saveWithTags(listOf(operation(1), operation(2), operation(3)), listOf(tag(1), tag(3)))

        val result = dao.operationsBefore(incomingNative, Long.MAX_VALUE, 10, sinceTimestamp = 2)

        assertEquals(listOf(3L), result.map { it.id })
    }

    @Test
    fun oldestTimestamp_emptyStorage_returnsNull() = runTest {
        assertNull(database.operationDao().oldestTimestamp(TagQuery(null, null, null)))
    }

    @Test
    fun oldestTimestamp_noFilter_returnsMinimum() = runTest {
        saveOperations(5, 3, 8)

        assertEquals(3L, database.operationDao().oldestTimestamp(TagQuery(null, null, null)))
    }

    @Test
    fun oldestTimestamp_tagFilter_returnsMinimumAmongMatches() = runTest {
        val dao = database.operationDao()
        dao.saveWithTags(listOf(operation(1), operation(2), operation(3)), listOf(tag(2), tag(3)))

        assertEquals(2L, dao.oldestTimestamp(incomingNative))
    }

    @Test
    fun oldestTimestamp_filterWithoutMatches_returnsNull() = runTest {
        saveOperations(1, 2)

        assertNull(database.operationDao().oldestTimestamp(incomingNative))
    }

    private suspend fun saveOperations(vararg ids: Long) {
        database.operationDao().saveWithTags(ids.map(::operation), emptyList())
    }

    private suspend fun ids(fromId: Long = Long.MAX_VALUE, sinceTimestamp: Long? = null, untilTimestamp: Long? = null) =
        database.operationDao()
            .operationsBefore(TagQuery(null, null, null), fromId, 10, sinceTimestamp, untilTimestamp)
            .map { it.id }

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
