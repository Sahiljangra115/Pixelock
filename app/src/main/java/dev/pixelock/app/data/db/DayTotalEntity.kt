package dev.pixelock.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** One row per day. Stats screens read only this table. */
@Entity(tableName = "day_totals")
data class DayTotalEntity(
    @PrimaryKey val date: String,
    val focusedMin: Int = 0,
    val distractedMs: Long = 0L,
    /** Finished timers that day: the height of that day's tower. */
    val blocks: Int = 0,
    /** Focused minutes from the laptop script. Counted on the group board, not on the towers. */
    @ColumnInfo(defaultValue = "0") val laptopMin: Int = 0
) {
    val distractedMin: Int get() = (distractedMs / 60_000L).toInt()
}
