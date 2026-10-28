package io.horizontalsystems.stellarkit.room

import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.sqlcipher.room.SqlCipherDatabases
import java.io.File

// The namespace names the on-disk manifest and lock files (`.stellar-kit-sqlcipher*`); it must never change.
internal val stellarKitDatabases = SqlCipherDatabases("stellar-kit")

// Each database is its own migration group keyed by its path, so clearing it also drops its interrupted migration.
internal suspend fun migrateDatabaseFile(file: File, databaseKey: ByteArray): DatabaseMigrationResult =
    stellarKitDatabases.migrateDatabases(file.directoryPath, listOf(file.name), file.path, databaseKey)

internal fun clearDatabaseFile(file: File) {
    stellarKitDatabases.clearDatabases(file.directoryPath, listOf(file.name), file.path)
}

internal fun requireValidDatabaseArguments(name: String, databaseKey: ByteArray) {
    require(databaseKey.size == DATABASE_KEY_SIZE) { "Database key must contain exactly $DATABASE_KEY_SIZE bytes" }
    val fileName = File(name).name
    require(fileName.isNotBlank()) { "Database file name must not be blank: $name" }
    require(RESERVED_PREFIXES.none { fileName.startsWith(it) }) {
        "Database file name uses a reserved migration prefix: $name"
    }
    // Contains, not endsWith: the SQLite family of a staging file (-wal, -shm, ...) is recovered too.
    require(RESERVED_SUFFIXES.none { fileName.contains(it) }) {
        "Database file name uses a reserved migration suffix: $name"
    }
}

private const val DATABASE_KEY_SIZE = 32

// Mirror sqlcipher-room's private file names, so a wallet database never collides with migration files.
private val RESERVED_PREFIXES = listOf(".stellar-kit-sqlcipher", ".bitcoin-kit-sqlcipher", ".tron-kit-sqlcipher")
private val RESERVED_SUFFIXES = listOf(".sqlcipher-migrating", ".plaintext-backup")

private val File.directoryPath: String
    get() = checkNotNull(absoluteFile.parent) { "Database path has no parent directory: $path" }
