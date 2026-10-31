package dev.pixelock.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [AppUsageEntity::class, DayTotalEntity::class],
    version = 2,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun appUsageDao(): AppUsageDao
    abstract fun dayTotalDao(): DayTotalDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE day_totals ADD COLUMN laptopMin INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile private var INSTANCE: AppDatabase? = null

        /**
         * No destructive fallback: friends must not lose stats on update.
         * No multi instance invalidation: its service, bound from the blocker process, keeps the
         * main process at visible priority forever. Screens read fresh rows each time they start.
         */
        fun getInstance(context: Context): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "pixelock.db"
                ).addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
