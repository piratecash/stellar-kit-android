package io.horizontalsystems.stellarkit.room

import io.horizontalsystems.stellarkit.PlatformContext
import java.io.File

internal actual fun databaseFile(context: PlatformContext, name: String): File =
    context.getDatabasePath(name)
