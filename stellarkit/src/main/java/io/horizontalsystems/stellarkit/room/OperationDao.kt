package io.horizontalsystems.stellarkit.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Transaction
import androidx.sqlite.SQLiteStatement
import io.horizontalsystems.stellarkit.TagQuery

@Dao
interface OperationDao {

    suspend fun operationsBefore(
        tagQuery: TagQuery,
        fromId: Long?,
        limit: Int,
        sinceTimestamp: Long? = null,
        untilTimestamp: Long? = null,
    ): List<Operation> {
        return operations(tagQuery, fromId, true, limit, sinceTimestamp, untilTimestamp)
    }

    suspend fun operationsAfter(tagQuery: TagQuery, fromId: Long?, limit: Int): List<Operation> {
        return operations(tagQuery, fromId, false, limit)
    }

    suspend fun oldestTimestamp(tagQuery: TagQuery): Long? {
        val filter = OperationFilter(tagQuery, null, true, null, null)

        return oldestTimestamp(
            RoomRawQuery("SELECT MIN(Operation.timestamp) FROM Operation ${filter.joinClause} ${filter.whereClause}", filter::bind)
        )
    }

    private suspend fun operations(
        tagQuery: TagQuery,
        fromId: Long?,
        descending: Boolean,
        limit: Int,
        sinceTimestamp: Long? = null,
        untilTimestamp: Long? = null,
    ): List<Operation> {
        val filter = OperationFilter(tagQuery, fromId, descending, sinceTimestamp, untilTimestamp)
        val limitClause = "LIMIT $limit"
        val orderClause = "ORDER BY operation.id ${if (descending) "DESC" else "ASC"}"

        val sql = """
            SELECT DISTINCT Operation.*
            FROM Operation
            ${filter.joinClause}
            ${filter.whereClause}
            $orderClause
            $limitClause
            """

        return operations(RoomRawQuery(sql, filter::bind))
    }

    @RawQuery
    suspend fun operations(query: RoomRawQuery): List<Operation>

    @RawQuery
    suspend fun oldestTimestamp(query: RoomRawQuery): Long?

    @Query("SELECT * FROM Operation ORDER BY id DESC LIMIT 1")
    suspend fun latestOperation(): Operation?

    @Query("SELECT * FROM OperationSyncState LIMIT 1")
    suspend fun operationSyncState(): OperationSyncState?

    @Query("SELECT * FROM Operation ORDER BY id ASC LIMIT 1")
    suspend fun oldestOperation(): Operation?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(operationSyncState: OperationSyncState)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(operations: List<Operation>)

    @Transaction
    suspend fun saveWithTags(operations: List<Operation>, tags: List<Tag>) {
        save(operations)
        deleteTags(operations.map { it.id })
        insertTags(tags)
    }

    @Query("DELETE FROM Tag WHERE operationId IN (:operationIds)")
    suspend fun deleteTags(operationIds: List<Long>)

    @Insert
    suspend fun insertTags(tags: List<Tag>)
}

private class OperationFilter(
    tagQuery: TagQuery,
    fromId: Long?,
    descending: Boolean,
    sinceTimestamp: Long?,
    untilTimestamp: Long?,
) {
    private val arguments = mutableListOf<String>()
    private val whereConditions = mutableListOf<String>()
    val joinClause: String

    init {
        if (!tagQuery.isEmpty) {
            tagQuery.type?.let { type ->
                whereConditions.add("Tag.type = ?")
                arguments.add(type.name)
            }
            tagQuery.assetId?.let { assetId ->
                whereConditions.add("Tag.assetId = ?")
                arguments.add(assetId)
            }
            tagQuery.accountId?.let { accountId ->
                whereConditions.add("Tag.accountIds LIKE ?")
                arguments.add("%${accountId}%")
            }

            joinClause = "INNER JOIN tag ON operation.id = tag.operationId"
        } else {
            joinClause = ""
        }

        fromId?.let {
            val comparisonOperator = if (descending) "<" else ">"
            whereConditions.add("operation.id $comparisonOperator ?")
            arguments.add(it.toString())
        }
        sinceTimestamp?.let {
            whereConditions.add("operation.timestamp >= ?")
            arguments.add(it.toString())
        }
        untilTimestamp?.let {
            whereConditions.add("operation.timestamp < ?")
            arguments.add(it.toString())
        }
    }

    val whereClause: String
        get() = if (whereConditions.isEmpty()) "" else "WHERE ${whereConditions.joinToString(" AND ")}"

    fun bind(statement: SQLiteStatement) {
        arguments.forEachIndexed { index, argument -> statement.bindText(index + 1, argument) }
    }
}
