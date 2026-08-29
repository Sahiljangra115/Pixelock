package dev.pixelock.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface AppUsageDao {
    @Query("SELECT * FROM app_usage WHERE date = :date")
    suspend fun forDate(date: String): List<AppUsageEntity>

    @Query("INSERT OR IGNORE INTO app_usage(date, packageName, totalTime) VALUES(:date, :pkg, 0)")
    suspend fun ensure(date: String, pkg: String)

    @Query("UPDATE app_usage SET totalTime = totalTime + :ms WHERE date = :date AND packageName = :pkg")
    suspend fun bump(date: String, pkg: String, ms: Long)

    @Transaction
    suspend fun add(date: String, pkg: String, ms: Long) {
        ensure(date, pkg)
        bump(date, pkg, ms)
    }

    @Query("DELETE FROM app_usage WHERE date < :before")
    suspend fun deleteBefore(before: String)
}

@Dao
interface DayTotalDao {
    @Query("SELECT * FROM day_totals WHERE date BETWEEN :from AND :to ORDER BY date")
    fun observeRange(from: String, to: String): Flow<List<DayTotalEntity>>

    @Query("SELECT * FROM day_totals WHERE date BETWEEN :from AND :to ORDER BY date")
    suspend fun range(from: String, to: String): List<DayTotalEntity>

    @Query("INSERT OR IGNORE INTO day_totals(date, focusedMin, distractedMs, blocks) VALUES(:date, 0, 0, 0)")
    suspend fun ensure(date: String)

    @Query("UPDATE day_totals SET focusedMin = focusedMin + :min WHERE date = :date")
    suspend fun bumpFocused(date: String, min: Int)

    @Query("UPDATE day_totals SET distractedMs = distractedMs + :ms WHERE date = :date")
    suspend fun bumpDistracted(date: String, ms: Long)

    @Query("UPDATE day_totals SET blocks = blocks + 1 WHERE date = :date")
    suspend fun bumpBlocks(date: String)

    /** Keeps the bigger value, so rescanning an old code cannot lower a day. */
    @Query("UPDATE day_totals SET laptopMin = MAX(laptopMin, :min) WHERE date = :date")
    suspend fun raiseLaptop(date: String, min: Int)

    @Transaction
    suspend fun setLaptop(date: String, min: Int) {
        ensure(date)
        raiseLaptop(date, min)
    }

    @Transaction
    suspend fun addFocused(date: String, min: Int) {
        ensure(date)
        bumpFocused(date, min)
    }

    @Transaction
    suspend fun addDistracted(date: String, ms: Long) {
        ensure(date)
        bumpDistracted(date, ms)
    }

    @Transaction
    suspend fun addBlock(date: String) {
        ensure(date)
        bumpBlocks(date)
    }
}
