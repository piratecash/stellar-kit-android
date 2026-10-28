package io.horizontalsystems.stellarkit.room

import androidx.room.Room
import androidx.room.RoomDatabase
import io.horizontalsystems.stellarkit.PlatformContext

internal fun kitDatabaseBuilder(
    context: PlatformContext,
    name: String,
    databaseKey: ByteArray,
): RoomDatabase.Builder<KitDatabase> {
    val file = databaseFile(context, name)
    // Verifies the file against the key before any directory is created.
    val builder = stellarKitDatabases.encrypted(Room.databaseBuilder<KitDatabase>(file.path), file.path, databaseKey)
    // Unlike Android's getDatabasePath, a JVM driver does not create the parent directory.
    file.absoluteFile.parentFile?.mkdirs()
    return builder
}
