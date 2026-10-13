package dev.pixelock.app.ui

import dev.pixelock.app.domain.DayValue
import dev.pixelock.app.ui.fragments.StatsMode
import dev.pixelock.app.ui.fragments.StatsPeriod
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class StatsPeriodTest {

    private val friday = LocalDate.of(2026, 10, 30)

    @Test fun `day mode shows the sunday to saturday week`() {
        val p = StatsPeriod(StatsMode.DAY, friday)
        assertEquals(DayOfWeek.SUNDAY, p.from.dayOfWeek)
        assertEquals(7, p.columns(emptyList()).size)
    }

    @Test fun `month mode has one column per day, and handles february`() {
        assertEquals(31, StatsPeriod(StatsMode.MONTH, friday).columns(emptyList()).size)
        assertEquals(28, StatsPeriod(StatsMode.MONTH, LocalDate.of(2026, 2, 10)).columns(emptyList()).size)
    }

    @Test fun `year mode adds days into twelve months`() {
        val days = listOf(
            DayValue(friday, focusedMin = 10),
            DayValue(friday.minusDays(1), focusedMin = 5),
            DayValue(LocalDate.of(2026, 1, 2), focusedMin = 7)
        )
        val columns = StatsPeriod(StatsMode.YEAR, friday).columns(days)
        assertEquals(12, columns.size)
        assertEquals(15, columns[9].focusedMin)
        assertEquals(7, columns[0].focusedMin)
    }

    @Test fun `shifting a month from the 31st stays valid`() {
        val p = StatsPeriod(StatsMode.MONTH, LocalDate.of(2026, 3, 31)).shifted(-1)
        assertEquals(LocalDate.of(2026, 2, 1), p.from)
    }
}
