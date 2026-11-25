package io.horizontalsystems.stellarkit.room

import io.horizontalsystems.databasenaming.DatabaseNameResolver
import io.horizontalsystems.sqlcipher.room.DatabaseMigrationResult
import io.horizontalsystems.sqlcipher.room.SqlCipherDatabases
import java.io.File

// The namespace names the on-disk manifest and lock files (`.stellar-kit-sqlcipher*`); the default must never change.
private const val NAMESPACE = "stellar-kit"

internal fun stellarKitDatabases(nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity) =
    SqlCipherDatabases(nameResolver.resolve(NAMESPACE))

// Each database is its own migration group keyed by its path, so clearing it also drops its interrupted migration.
internal suspend fun migrateDatabaseFile(
    file: File,
    databaseKey: ByteArray,
    nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity,
): DatabaseMigrationResult =
    stellarKitDatabases(nameResolver).migrateDatabases(file.directoryPath, listOf(file.name), file.path, databaseKey)

internal fun clearDatabaseFile(file: File, nameResolver: DatabaseNameResolver = DatabaseNameResolver.Identity) {
    stellarKitDatabases(nameResolver).clearDatabases(file.directoryPath, listOf(file.name), file.path)
}

/** The file [nameResolver] maps the default [defaultName] to, checked with [databaseKey] before any I/O. */
internal fun resolvedDatabaseName(
    defaultName: String,
    databaseKey: ByteArray,
    nameResolver: DatabaseNameResolver,
): String {
    require(databaseKey.size == DATABASE_KEY_SIZE) { "Database key must contain exactly $DATABASE_KEY_SIZE bytes" }
    val name = nameResolver.resolve(defaultName)
    val fileName = File(name).name
    val reservedPrefixes = RESERVED_PREFIXES + ".${nameResolver.resolve(NAMESPACE)}-sqlcipher"
    require(fileName.isNotBlank()) { "Database file name must not be blank: $name" }
    require(reservedPrefixes.none { fileName.startsWith(it) }) {
        "Database file name uses a reserved migration prefix: $name"
    }
    // Contains, not endsWith: the SQLite family of a staging file (-wal, -shm, ...) is recovered too.
    require(RESERVED_SUFFIXES.none { fileName.contains(it) }) {
        "Database file name uses a reserved migration suffix: $name"
    }
    return name
}

private const val DATABASE_KEY_SIZE = 32

// Mirror sqlcipher-room's private file names, so a wallet database never collides with migration files.
private val RESERVED_PREFIXES = listOf(".stellar-kit-sqlcipher", ".bitcoin-kit-sqlcipher", ".tron-kit-sqlcipher")
private val RESERVED_SUFFIXES = listOf(".sqlcipher-migrating", ".plaintext-backup")

private val File.directoryPath: String
    get() = checkNotNull(absoluteFile.parent) { "Database path has no parent directory: $path" }
