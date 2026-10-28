package io.horizontalsystems.stellarkit.room

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class KitDatabaseVersion2CompatibilityTest {
    @Test
    fun opensFixture_allValuesMatchWhatWasWritten() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dbName = "stellar-v2-compatibility-test.db"
        StellarV2Fixture.copyTo(context.getDatabasePath(dbName))

        // Robolectric cannot load the native SQLCipher library, so the plaintext fixture is opened by a plain
        // Room builder with the kit's schema and migrations; the encrypted path is covered on desktop and device.
        val database = Room.databaseBuilder(context, KitDatabase::class.java, dbName)
            .addMigrations(KitDatabase.MIGRATION_1_2)
            .build()

        StellarV2Fixture.assertContents(database)

        database.close()
    }
}
