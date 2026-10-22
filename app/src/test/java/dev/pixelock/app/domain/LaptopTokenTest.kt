package dev.pixelock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class LaptopTokenTest {

    private val today = LocalDate.of(2026, 10, 30)
    private val key = "TESTKEY"

    private fun token(date: String, minutes: Any, key: String = this.key): String {
        val body = "PXL1.$date.$minutes"
        return "$body.${LaptopToken.signature(body, key)}"
    }

    @Test fun `reads a code made by the python script`() {
        val fromPython = "PXL1.2026-10-30.95.54ec75c4e56ea0345428f0494ccce655"
        assertEquals(LaptopDay(today, 95), LaptopToken.parse(fromPython, key, today))
    }

    @Test fun `a changed number breaks the signature`() {
        val good = token("2026-10-30", 95)
        assertNull(LaptopToken.parse(good.replace(".95.", ".950."), key, today))
    }

    @Test fun `another key is refused, and so is no key`() {
        assertNull(LaptopToken.parse(token("2026-10-30", 95, "OTHER"), key, today))
        assertNull(LaptopToken.parse(token("2026-10-30", 95, "OTHER"), "", today))
    }

    @Test fun `dates must be recent and not in the future`() {
        assertEquals(7, java.time.temporal.ChronoUnit.DAYS.between(LocalDate.of(2026, 10, 23), today))
        assertTrue(LaptopToken.parse(token("2026-10-23", 10), key, today) != null)
        assertNull(LaptopToken.parse(token("2026-10-22", 10), key, today))
        assertNull(LaptopToken.parse(token("2026-10-31", 10), key, today))
        assertNull(LaptopToken.parse(token("not-a-date", 10), key, today))
    }

    @Test fun `minutes must fit in a day`() {
        assertNull(LaptopToken.parse(token("2026-10-30", 1441), key, today))
        assertNull(LaptopToken.parse(token("2026-10-30", -1), key, today))
        assertNull(LaptopToken.parse(token("2026-10-30", "x"), key, today))
    }

    @Test fun `junk is not a token`() {
        assertFalse(LaptopToken.isToken("23456789AB"))
        assertNull(LaptopToken.parse("PXL1.a.b", key, today))
        assertNull(LaptopToken.parse("", key, today))
    }

    @Test fun `new keys are long and differ`() {
        assertEquals(16, LaptopToken.newKey().length)
        assertTrue(LaptopToken.newKey() != LaptopToken.newKey())
    }
}
