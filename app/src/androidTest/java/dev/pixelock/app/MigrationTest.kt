package dev.pixelock.app

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.pixelock.app.data.db.AppDatabase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val name = "migration-test"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test fun oneToTwoKeepsStats() {
        helper.createDatabase(name, 1).use { db ->
            db.execSQL("INSERT INTO day_totals(date, focusedMin, distractedMs, blocks) VALUES('2026-10-30', 45, 60000, 2)")
        }
        val db = helper.runMigrationsAndValidate(name, 2, true, AppDatabase.MIGRATION_1_2)
        db.query("SELECT focusedMin, distractedMs, blocks, laptopMin FROM day_totals").use { row ->
            assertTrue(row.moveToFirst())
            assertEquals(45, row.getInt(0))
            assertEquals(60000L, row.getLong(1))
            assertEquals(2, row.getInt(2))
            assertEquals(0, row.getInt(3))
        }
    }
}
