package io.horizontalsystems.stellarkit.room

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RawTransactionBroadcastDaoTest {
    private lateinit var database: KitDatabase
    private lateinit var dao: RawTransactionBroadcastDao

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            KitDatabase::class.java
        ).allowMainThreadQueries().build()
        dao = database.rawTransactionBroadcastDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun insert_duplicateTxHash_ignoresNewRecord() {
        dao.insert(record(retryCount = 0))
        dao.insert(record(retryCount = 5))

        val records = dao.dueRecords(nowSeconds = 1_000)

        assertEquals(1, records.size)
        assertEquals(0, records.single().retryCount)
    }

    @Test
    fun dueRecords_filtersByNextRetryAndValidUntil() {
        dao.insert(record(txHash = "due", nextRetryAt = 900, validUntil = 2_000))
        dao.insert(record(txHash = "future", nextRetryAt = 1_100, validUntil = 2_000))
        dao.insert(record(txHash = "expired", nextRetryAt = 900, validUntil = 1_000))

        val records = dao.dueRecords(nowSeconds = 1_000)

        assertEquals(listOf("due"), records.map { it.txHash })
    }

    @Test
    fun update_existingRecord_updatesRetryState() {
        val initialRecord = record(retryCount = 0, nextRetryAt = 1_000)
        dao.insert(initialRecord)

        dao.update(initialRecord.retried(nextRetryAt = 1_500))

        val record = dao.dueRecords(nowSeconds = 1_500).single()
        assertEquals(1, record.retryCount)
        assertEquals(1_500L, record.nextRetryAt)
    }

    private fun record(
        txHash: String = "tx-hash",
        retryCount: Int = 0,
        nextRetryAt: Long = 900,
        validUntil: Long = 2_000,
    ) = RawTransactionBroadcastRecord(
        txHash = txHash,
        raw = byteArrayOf(1, 2, 3),
        sourceAccountId = "source",
        sequenceNumber = 2,
        validUntil = validUntil,
        createdAt = 500,
        retryCount = retryCount,
        nextRetryAt = nextRetryAt,
    )
}
