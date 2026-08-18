package dev.pixelock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class StrictRulesTest {

    private val session = FocusSession("s", 1_000L, 10_000L)
    private val boot = BootStamp(bootCount = 7, elapsedMs = 500L)
    private val armed = StrictRules.arm(StrictState(enabled = true), session, boot)

    private fun enforcing(
        state: StrictState = armed,
        s: FocusSession? = session,
        b: BootStamp = boot.copy(elapsedMs = 900L),
        failSafe: Boolean = false
    ) = StrictRules.isEnforcing(state, s, b, failSafe)

    @Test fun `armed session is enforced`() = assertTrue(enforcing())

    @Test fun `strict off never arms`() {
        val state = StrictRules.arm(StrictState(enabled = false), session, boot)
        assertNull(state.armedSessionId)
        assertFalse(enforcing(state))
    }

    @Test fun `strict ends with the timer`() {
        // The session is 9 seconds long and was armed at elapsed 500.
        assertTrue(enforcing(b = boot.copy(elapsedMs = 9_499L)))
        assertFalse(enforcing(b = boot.copy(elapsedMs = 9_500L)))
        assertFalse(enforcing(s = null))
    }

    @Test fun `the wall clock plays no part, so the date cannot end or stretch strict`() {
        assertTrue(enforcing(s = session.copy(), b = boot.copy(elapsedMs = 9_000L)))
        val longAgo = session.copy(startedAtMs = 1_000L - 86_400_000L, endsAtMs = 10_000L - 86_400_000L)
        assertTrue(enforcing(s = longAgo))
        assertFalse(enforcing(s = longAgo, b = boot.copy(elapsedMs = 9_500L)))
    }

    @Test fun `restart ends strict by boot count`() = assertFalse(enforcing(b = BootStamp(8, 900L)))

    @Test fun `restart ends strict when elapsed time goes backward`() =
        assertFalse(enforcing(b = BootStamp(7, 100L)))

    @Test fun `kill switch ends strict`() =
        assertFalse(enforcing(armed.copy(enforcementDisabled = true)))

    @Test fun `crash breaker ends strict`() = assertFalse(enforcing(failSafe = true))

    @Test fun `payment disarms strict`() = assertFalse(enforcing(StrictRules.disarm(armed)))

    @Test fun `a new session does not inherit the old arm`() =
        assertFalse(enforcing(s = session.copy(id = "other")))

    @Test fun `every timer is held by the boot clock, strict or not`() {
        val plain = StrictRules.arm(StrictState(enabled = false), session, boot)
        val pastItsDate = boot.copy(elapsedMs = 900L)
        assertTrue(StrictRules.isHolding(plain, session, pastItsDate, false))
        assertFalse(StrictRules.isHolding(plain, session, boot.copy(elapsedMs = 9_500L), false))
        assertFalse(StrictRules.isHolding(plain, session, BootStamp(8, 900L), false))
        assertFalse(StrictRules.isHolding(StrictRules.disarm(plain), session, pastItsDate, false))
        assertFalse(StrictRules.isHolding(plain.copy(enforcementDisabled = true), session, pastItsDate, false))
    }

    @Test fun `strict switched on mid timer holds it for the time left, and no longer`() {
        // 3 seconds into the 9 second timer, at elapsed 5000.
        val state = StrictRules.armRunning(StrictState(), session, BootStamp(7, 5_000L), 4_000L)
        assertTrue(state.enabled)
        assertTrue(enforcing(state, b = BootStamp(7, 5_000L)))
        assertTrue(enforcing(state, b = BootStamp(7, 10_999L)))
        assertFalse(enforcing(state, b = BootStamp(7, 11_000L)))
    }

    @Test fun `strict switched on mid timer keeps the boot clock, so a moved date changes nothing`() {
        val plain = StrictRules.arm(StrictState(), session, boot)
        val state = StrictRules.armRunning(plain, session, boot.copy(elapsedMs = 2_000L), 99_000_000L)
        assertEquals(boot.elapsedMs, state.armedElapsedMs)
        assertTrue(enforcing(state, b = boot.copy(elapsedMs = 9_000L)))
    }

    @Test fun `ledger counts whole minutes and keeps the remainder`() {
        val zone = ZoneId.of("UTC")
        val start = ZonedDateTime.of(2026, 10, 30, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
        val s = FocusSession("s", start, start + 3 * 60_000)
        val result = focusMinutes(s, emptyList(), start, start + 5 * 60_000 + 30_000, zone)
        assertEquals(mapOf(java.time.LocalDate.of(2026, 10, 30) to 3), result.minutesByDate)
        assertEquals(start + 5 * 60_000, result.accountedUntilMs)
    }

    @Test fun `ledger splits a routine across midnight`() {
        val zone = ZoneId.of("UTC")
        val at = ZonedDateTime.of(2026, 10, 30, 23, 58, 0, 0, zone).toInstant().toEpochMilli()
        val r = Routine(enabled = true, startMin = 23 * 60, endMin = 60)
        val result = focusMinutes(null, listOf(r), at, at + 4 * 60_000, zone)
        assertEquals(2, result.minutesByDate[java.time.LocalDate.of(2026, 10, 30)])
        assertEquals(2, result.minutesByDate[java.time.LocalDate.of(2026, 10, 31)])
    }

    @Test fun `ledger caps a clock jump at seven days`() {
        val zone = ZoneId.of("UTC")
        val r = Routine(enabled = true, startMin = 0, endMin = 1440)
        val result = focusMinutes(null, listOf(r), 0L, 365L * 24 * 3_600_000, zone)
        assertTrue(result.minutesByDate.values.sum() <= 7 * 1440)
    }
}
