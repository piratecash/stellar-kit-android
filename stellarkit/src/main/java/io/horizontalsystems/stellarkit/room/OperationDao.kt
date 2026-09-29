package io.horizontalsystems.stellarkit.room

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.RoomRawQuery
import androidx.room.Transaction
import io.horizontalsystems.stellarkit.TagQuery

@Dao
interface OperationDao {

    suspend fun operationsBefore(tagQuery: TagQuery, fromId: Long?, limit: Int): List<Operation> {
        return operations(tagQuery, fromId, true, limit)
    }

    suspend fun operationsAfter(tagQuery: TagQuery, fromId: Long?, limit: Int): List<Operation> {
        return operations(tagQuery, fromId, false, limit)
    }

    private suspend fun operations(tagQuery: TagQuery, fromId: Long?, descending: Boolean, limit: Int): List<Operation> {
        val arguments = mutableListOf<String>()
        val whereConditions = mutableListOf<String>()
        var joinClause = ""

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
        }

        fromId?.let {
            val comparisonOperator = if (descending) "<" else ">"
            whereConditions.add("operation.id $comparisonOperator ?")
            arguments.add(it.toString())
        }

        val limitClause = "LIMIT $limit"
        val orderClause = "ORDER BY operation.id ${if (descending) "DESC" else "ASC"}"
        val whereClause = if (whereConditions.size > 0) {
            "WHERE ${whereConditions.joinToString(" AND ")}"
        } else {
            ""
        }

        val sql = """
            SELECT DISTINCT Operation.*
            FROM Operation
            $joinClause
            $whereClause
            $orderClause
            $limitClause
            """

        val query = RoomRawQuery(sql) { statement ->
            arguments.forEachIndexed { index, argument -> statement.bindText(index + 1, argument) }
        }

        return operations(query)
    }

    @RawQuery
    suspend fun operations(query: RoomRawQuery): List<Operation>

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