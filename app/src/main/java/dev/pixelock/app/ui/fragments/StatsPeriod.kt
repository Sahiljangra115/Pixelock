package dev.pixelock.app.ui.fragments

import dev.pixelock.app.domain.DayValue
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.time.DayOfWeek

enum class StatsMode { DAY, MONTH, YEAR }

/** A stats period: the date range for a mode around [anchor]. */
data class StatsPeriod(val mode: StatsMode, val anchor: LocalDate) {

    val from: LocalDate = when (mode) {
        StatsMode.DAY -> anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
        StatsMode.MONTH -> anchor.withDayOfMonth(1)
        StatsMode.YEAR -> anchor.withDayOfYear(1)
    }

    val to: LocalDate = when (mode) {
        StatsMode.DAY -> from.plusDays(6)
        StatsMode.MONTH -> from.plusMonths(1).minusDays(1)
        StatsMode.YEAR -> from.plusYears(1).minusDays(1)
    }

    fun shifted(step: Long): StatsPeriod = copy(
        anchor = when (mode) {
            StatsMode.DAY -> anchor.plusWeeks(step)
            StatsMode.MONTH -> anchor.plusMonths(step)
            StatsMode.YEAR -> anchor.plusYears(step)
        }
    )

    /** Columns on the chart: one per day, or one per month in a year. */
    fun columns(days: List<DayValue>): List<DayValue> {
        val byDate = days.associateBy { it.date }
        if (mode == StatsMode.YEAR) {
            return (1..12).map { month ->
                val inMonth = days.filter { it.date.monthValue == month }
                DayValue(
                    from.withMonth(month),
                    inMonth.sumOf { it.focusedMin },
                    inMonth.sumOf { it.distractedMin },
                    inMonth.sumOf { it.blocks }
                )
            }
        }
        return generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .map { byDate[it] ?: DayValue(it) }
            .toList()
    }
}
