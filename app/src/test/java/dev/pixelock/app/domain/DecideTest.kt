package dev.pixelock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class DecideTest {

    private val zone = ZoneId.of("Asia/Kolkata")

    // Friday 30 Oct 2026, 15:00.
    private val friday3pm = ZonedDateTime.of(2026, 10, 30, 15, 0, 0, 0, zone)
    private val nowMs = friday3pm.toInstant().toEpochMilli()
    private val session = FocusSession("s", nowMs - 60_000, nowMs + 60_000)

    private val insta = "com.instagram.android"
    private val dialer = "com.android.dialer"

    private val base = PolicyState(
        focusBlockList = setOf(insta),
        shortVideoBlockingOn = true,
        alwaysAllow = setOf(dialer)
    )

    private fun app(pkg: String = insta) = ForegroundState(pkg)
    private fun reel(pkg: String = insta) = ForegroundState(pkg, Surface.SHORT_VIDEO)

    @Test fun `nothing is blocked with no session and no bag`() {
        assertEquals(Decision.Allow, decide(app(), base, friday3pm))
    }

    @Test fun `reels are blocked but the rest of the app is not`() {
        assertEquals(Decision.Block(BlockReason.SHORT_VIDEO), decide(reel(), base, friday3pm))
        assertEquals(Decision.Allow, decide(app(), base, friday3pm))
    }

    @Test fun `short video toggle off lets reels through`() {
        assertEquals(Decision.Allow, decide(reel(), base.copy(shortVideoBlockingOn = false), friday3pm))
    }

    @Test fun `listed apps are blocked during a session until it ends`() {
        val policy = base.copy(session = session)
        assertEquals(Decision.Block(BlockReason.SESSION, session.endsAtMs), decide(app(), policy, friday3pm))
        assertEquals(Decision.Allow, decide(app("com.example.notes"), policy, friday3pm))
    }

    @Test fun `an expired session blocks nothing even if nobody cleared it`() {
        val policy = base.copy(session = session)
        assertEquals(Decision.Allow, decide(app(), policy, friday3pm.plusMinutes(2)))
    }

    @Test fun `always allowed apps beat every rule`() {
        val everything = base.copy(
            session = session,
            focusBlockList = setOf(dialer),
            bags = listOf(Bag(packages = listOf(dialer), dailyLimitMin = 0)),
            routines = listOf(Routine(enabled = true, startMin = 0, endMin = 1440))
        )
        assertEquals(Decision.Allow, decide(app(dialer), everything, friday3pm))
        assertEquals(Decision.Allow, decide(reel(dialer), everything, friday3pm))
    }

    @Test fun `kill switch and crash breaker turn all blocking off`() {
        val policy = base.copy(session = session)
        assertEquals(Decision.Allow, decide(app(), policy.copy(enforcementDisabled = true), friday3pm))
        assertEquals(Decision.Allow, decide(reel(), policy.copy(failSafe = true), friday3pm))
    }

    @Test fun `a paid pass never opens a short video feed`() {
        val policy = base.copy(shortVideoBlockingOn = true, unlocks = listOf(Unlock(insta, nowMs + 1000)))
        val reels = decide(ForegroundState(insta, Surface.SHORT_VIDEO), policy, friday3pm)
        assertEquals(BlockReason.SHORT_VIDEO, (reels as Decision.Block).reason)
    }

    @Test fun `strict keeps blocking when the date is moved past the end`() {
        val ended = FocusSession("s", nowMs - 7_200_000, nowMs - 3_600_000)
        val policy = base.copy(session = ended)
        assertEquals(Decision.Allow, decide(app(), policy, friday3pm))
        assertTrue(decide(app(), policy.copy(timerHolding = true), friday3pm) is Decision.Block)
    }

    @Test fun `unlock opens one app until it expires`() {
        val policy = base.copy(session = session, unlocks = listOf(Unlock(insta, nowMs + 1000)))
        assertEquals(Decision.Allow, decide(app(), policy, friday3pm))
        assertTrue(decide(app(), policy.copy(unlocks = listOf(Unlock(insta, nowMs))), friday3pm) is Decision.Block)
        assertTrue(decide(app(), policy.copy(unlocks = listOf(Unlock("other", nowMs + 1000))), friday3pm) is Decision.Block)
    }

    @Test fun `routine blocks on its days only`() {
        val fridayOnly = Routine(enabled = true, days = setOf(5), startMin = 14 * 60, endMin = 16 * 60)
        val policy = base.copy(routines = listOf(fridayOnly))
        val end = friday3pm.plusHours(1).toInstant().toEpochMilli()
        assertEquals(Decision.Block(BlockReason.ROUTINE, end), decide(app(), policy, friday3pm))
        assertEquals(Decision.Allow, decide(app(), policy, friday3pm.plusDays(1)))
    }

    @Test fun `disabled and zero length routines block nothing`() {
        val off = Routine(enabled = false, startMin = 0, endMin = 1440)
        val empty = Routine(enabled = true, startMin = 600, endMin = 600)
        assertEquals(Decision.Allow, decide(app(), base.copy(routines = listOf(off, empty)), friday3pm))
    }

    @Test fun `routine past midnight runs into the next day`() {
        val thursdayNight = Routine(enabled = true, days = setOf(4), startMin = 23 * 60, endMin = 2 * 60)
        val policy = base.copy(routines = listOf(thursdayNight))
        val friday1am = friday3pm.withHour(1)
        assertTrue(decide(app(), policy, friday1am) is Decision.Block)
        assertEquals(Decision.Allow, decide(app(), policy, friday1am.withHour(3)))
        assertTrue(decide(app(), policy, friday1am.minusDays(1).withHour(23)) is Decision.Block)
    }

    @Test fun `bag limit closes the bag once used`() {
        val bag = Bag(packages = listOf("a", "b"), dailyLimitMin = 30)
        val policy = base.copy(bags = listOf(bag), usedMinToday = mapOf("a" to 20, "b" to 9))
        assertEquals(Decision.Allow, decide(app("a"), policy, friday3pm))
        val spent = policy.copy(usedMinToday = mapOf("a" to 20, "b" to 10))
        assertTrue(decide(app("a"), spent, friday3pm) is Decision.Block)
    }

    @Test fun `bag window opens only inside the window`() {
        val bag = Bag(packages = listOf("a"), mode = BagMode.WINDOW, startMin = 13 * 60, endMin = 14 * 60)
        val policy = base.copy(bags = listOf(bag))
        val nextWindow = friday3pm.plusDays(1).withHour(13).toInstant().toEpochMilli()
        assertEquals(Decision.Block(BlockReason.BAG, nextWindow), decide(app("a"), policy, friday3pm))
        assertEquals(Decision.Allow, decide(app("a"), policy, friday3pm.withHour(13)))
    }

    @Test fun `bag apps are blocked during a session even inside their window`() {
        val bag = Bag(packages = listOf("a"), mode = BagMode.WINDOW, startMin = 14 * 60, endMin = 16 * 60)
        val policy = base.copy(bags = listOf(bag), session = session)
        assertTrue(decide(app("a"), policy, friday3pm) is Decision.Block)
    }

    @Test fun `empty package is never blocked`() {
        assertEquals(Decision.Allow, decide(app(""), base.copy(session = session), friday3pm))
    }

    @Test fun `locked items refuse edits during a session and strict refuses all`() {
        assertTrue(canEdit(locked = true, sessionActive = false, strictEnforcing = false))
        assertTrue(canEdit(locked = false, sessionActive = true, strictEnforcing = false))
        assertFalse(canEdit(locked = true, sessionActive = true, strictEnforcing = false))
        assertFalse(canEdit(locked = false, sessionActive = true, strictEnforcing = true))
    }
}
