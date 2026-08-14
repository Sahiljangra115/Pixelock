package dev.pixelock.app.domain

import java.time.LocalDate

data class DayValue(
    val date: LocalDate,
    val focusedMin: Int = 0,
    val distractedMin: Int = 0,
    val blocks: Int = 0
)

data class Summary(
    val bestDayMin: Int = 0,
    val totalMin: Int = 0,
    val avgMinPerDay: Int = 0,
    val tallestTower: Int = 0
)

/** Summary of one period. The average counts only days up to [today], so a new month reads true. */
fun summarize(days: List<DayValue>, today: LocalDate): Summary {
    val past = days.filter { !it.date.isAfter(today) }
    if (past.isEmpty()) return Summary()
    val total = past.sumOf { it.focusedMin }
    return Summary(
        bestDayMin = past.maxOf { it.focusedMin },
        totalMin = total,
        avgMinPerDay = total / past.size,
        tallestTower = past.maxOf { it.blocks }
    )
}
