package io.horizontalsystems.stellarkit.room

import androidx.room.Room
import androidx.room.RoomDatabase
import io.horizontalsystems.stellarkit.PlatformContext

internal fun kitDatabaseBuilder(
    context: PlatformContext,
    name: String,
    databaseKey: ByteArray,
): RoomDatabase.Builder<KitDatabase> =
    stellarKitDatabases.encrypted(
        Room.databaseBuilder(context, KitDatabase::class.java, name),
        databaseFile(context, name).path,
        databaseKey,
    )
