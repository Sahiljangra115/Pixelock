package dev.pixelock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class PauseAndEndTest {

    private val minute = 60_000L
    private val zone = ZoneId.of("UTC")
    private val start = ZonedDateTime.of(2026, 10, 30, 10, 0, 0, 0, zone).toInstant().toEpochMilli()
    private val session = FocusSession("s", start, start + 60 * minute)
    private val app = ForegroundState("com.instagram.android")

    private fun at(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone)
    private fun policy(s: FocusSession) = PolicyState(session = s, focusBlockList = setOf(app.packageName))

    @Test fun `a pause opens the app, then blocking comes back by itself`() {
        val paused = session.paused(start + minute)!!
        assertEquals(Decision.Allow, decide(app, policy(paused), at(start + 2 * minute)))
        assertTrue(decide(app, policy(paused), at(start + 7 * minute)) is Decision.Block)
    }

    @Test fun `a pause moves the end out, so no focus time is lost`() {
        val paused = session.paused(start + minute)!!
        assertEquals(session.endsAtMs + PAUSE_MIN * minute, paused.endsAtMs)
    }

    @Test fun `resuming early gives back the unused part`() {
        val paused = session.paused(start + minute)!!
        val resumed = paused.resumed(start + 2 * minute)!!
        assertEquals(session.endsAtMs + minute, resumed.endsAtMs)
        assertTrue(decide(app, policy(resumed), at(start + 2 * minute)) is Decision.Block)
    }

    @Test fun `resume does nothing when no pause is running`() = assertNull(session.resumed(start + minute))

    @Test fun `pauses run out`() {
        var s = session
        repeat(MAX_PAUSES) { i -> s = s.paused(start + i * 10 * minute)!! }
        assertNull(s.paused(start + 40 * minute))
    }

    @Test fun `a second pause cannot start inside the first`() =
        assertNull(session.paused(start + minute)!!.paused(start + 2 * minute))

    @Test fun `moving the date back cannot stretch a pause`() {
        val paused = session.paused(start + 30 * minute)!!
        assertFalse(paused.isPaused(start))
        assertTrue(decide(app, policy(paused), at(start)) is Decision.Block)
    }

    @Test fun `a pause does not open short video feeds`() {
        val paused = session.paused(start + minute)!!
        val reels = app.copy(surface = Surface.SHORT_VIDEO)
        val rules = policy(paused).copy(shortVideoBlockingOn = true)
        assertEquals(Decision.Block(BlockReason.SHORT_VIDEO), decide(reels, rules, at(start + 2 * minute)))
    }

    @Test fun `a pause does not open a routine`() {
        val paused = session.paused(start + minute)!!
        val rules = policy(paused).copy(routines = listOf(Routine(enabled = true, startMin = 0, endMin = 1439)))
        val result = decide(app, rules, at(start + 2 * minute))
        assertEquals(BlockReason.ROUTINE, (result as Decision.Block).reason)
    }

    @Test fun `paused time is not focus time`() {
        val paused = session.paused(start)!!
        val ledger = focusMinutes(paused, emptyList(), start, start + 10 * minute, zone)
        assertEquals(5, ledger.minutesByDate.values.sum())
    }

    @Test fun `adding time stops at the longest timer`() {
        val long = FocusSession("s", start, start + (MAX_SESSION_MIN - ADD_TIME_MIN) * minute)
        assertNotNull(long.withAddedTime())
        assertNull(long.withAddedTime()!!.withAddedTime())
    }

    @Test fun `the password matches itself and nothing else`() {
        val salt = EndPassword.newSalt()
        val hash = EndPassword.hash("correct horse", salt)
        assertTrue(EndPassword.matches("correct horse", salt, hash))
        assertFalse(EndPassword.matches("Correct horse", salt, hash))
        assertFalse(EndPassword.matches("correct horse", EndPassword.newSalt(), hash))
        assertFalse(hash.contains("correct"))
    }

    @Test fun `old settings with no password fields still load`() {
        val s = com.google.gson.Gson().fromJson(
            """{"session":{"id":"t","startedAtMs":1,"endsAtMs":2}}""",
            dev.pixelock.app.data.models.Settings::class.java
        )
        assertEquals("", s.endPasswordHash)
        assertEquals(0, s.session!!.pausesUsed)
    }

    @Test fun `emails come out of however they were typed`() {
        assertEquals(
            listOf("a@x.co", "b.c@y.in"),
            parseEmails("a@x.co, b.c@y.in;a@x.co  not-an-address @nope")
        )
        assertTrue(isEmail("a@x.co"))
        assertFalse(isEmail("+919876543210"))
    }
}
