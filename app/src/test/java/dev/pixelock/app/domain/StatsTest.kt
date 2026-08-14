package dev.pixelock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class StatsTest {

    private val today = LocalDate.of(2026, 10, 30)

    @Test fun `empty period gives zeros`() = assertEquals(Summary(), summarize(emptyList(), today))

    @Test fun `summary reads best day, total, average and tallest tower`() {
        val days = listOf(
            DayValue(today.minusDays(1), focusedMin = 30, blocks = 1),
            DayValue(today, focusedMin = 90, blocks = 3)
        )
        assertEquals(Summary(90, 120, 60, 3), summarize(days, today))
    }

    @Test fun `future days never lower the average`() {
        val days = listOf(DayValue(today, focusedMin = 60), DayValue(today.plusDays(1)))
        assertEquals(60, summarize(days, today).avgMinPerDay)
    }
}
