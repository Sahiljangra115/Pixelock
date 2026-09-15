package dev.pixelock.app.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import dev.pixelock.app.data.DataStoreManager
import dev.pixelock.app.data.db.AppDatabase
import dev.pixelock.app.domain.EndPassword
import dev.pixelock.app.domain.FocusSession
import dev.pixelock.app.domain.MAX_SESSION_MIN
import dev.pixelock.app.domain.focusMinutes
import dev.pixelock.app.domain.paused
import dev.pixelock.app.domain.resumed
import dev.pixelock.app.domain.withAddedTime
import dev.pixelock.app.receivers.SessionAlarmReceiver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

enum class EndResult { ENDED, STRICT, WRONG_PASSWORD }

object SessionManager {

    /** Returns null when a timer is already running. */
    suspend fun start(context: Context, minutes: Int): FocusSession? {
        val now = System.currentTimeMillis()
        val strict = StrictModeController(context)
        var started: FocusSession? = null
        reconcile(context)
        DataStoreManager(context).update { s ->
            if (strict.isTimerRunning(s)) return@update s
            val session = FocusSession(
                id = UUID.randomUUID().toString(),
                startedAtMs = now,
                endsAtMs = now + minutes.coerceIn(1, MAX_SESSION_MIN) * 60_000L
            )
            started = session
            strict.armed(s, session).copy(focusAccountedUntilMs = now)
        }
        started?.let { scheduleEnd(context, it.endsAtMs) }
        return started
    }

    /** True when no password is set, which is only a timer from before passwords existed. */
    suspend fun passwordOk(context: Context, password: String): Boolean {
        val s = DataStoreManager(context).settings.first()
        if (s.endPasswordHash.isEmpty()) return true
        return withContext(Dispatchers.Default) { EndPassword.matches(password, s.endPasswordSalt, s.endPasswordHash) }
    }

    /** The only way a person ends a timer. A password never beats Strict Mode. */
    suspend fun endEarly(context: Context, password: String): EndResult {
        val strict = StrictModeController(context)
        reconcile(context)
        if (!passwordOk(context, password)) return EndResult.WRONG_PASSWORD
        var result = EndResult.STRICT
        DataStoreManager(context).update { s ->
            if (strict.isEnforcing(s)) return@update s
            result = EndResult.ENDED
            strict.disarmed(s).copy(session = null)
        }
        if (result == EndResult.ENDED) cancelEnd(context)
        return result
    }

    /** Refused during a timer, or a new password would be a way out of it. */
    suspend fun setPassword(context: Context, password: String): Boolean {
        val strict = StrictModeController(context)
        val salt = EndPassword.newSalt()
        val hash = withContext(Dispatchers.Default) { EndPassword.hash(password, salt) }
        var saved = false
        DataStoreManager(context).update { s ->
            if (strict.isTimerRunning(s)) return@update s
            saved = true
            s.copy(endPasswordSalt = salt, endPasswordHash = hash)
        }
        return saved
    }

    suspend fun pause(context: Context) = change(context) { session, now -> session.paused(now) }

    suspend fun resume(context: Context) = change(context) { session, now -> session.resumed(now) }

    suspend fun addTime(context: Context) = change(context) { session, _ -> session.withAddedTime() }

    private suspend fun change(context: Context, edit: (FocusSession, Long) -> FocusSession?) {
        val strict = StrictModeController(context)
        reconcile(context) // Pause time must not be counted as focus, so settle before it moves.
        var endsAt = 0L
        DataStoreManager(context).update { s ->
            val session = s.session?.takeIf { strict.isTimerRunning(s) } ?: return@update s
            val next = edit(session, System.currentTimeMillis()) ?: return@update s
            endsAt = next.endsAtMs
            s.copy(session = next)
        }
        if (endsAt > 0) scheduleEnd(context, endsAt)
    }

    /** The emergency stop. Must finish even if the screen closes, and must not depend on Room. */
    suspend fun killEverything(context: Context) = withContext(NonCancellable) {
        DataStoreManager(context).update(StrictModeController(context)::killed)
        cancelEnd(context)
        ServiceHealth.removeAdmin(context)
    }

    /** Safe to call any time, from any process. Accounts focus time and clears what has ended. */
    suspend fun reconcile(context: Context) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val strict = StrictModeController(context)
        // ponytail: checked now, not per minute. A blocker turned off and on between two calls still counts.
        val blocking = ServiceHealth.isBlockerRunning(context) && !CrashGuard.isFailSafe(context)
        var minutes: Map<LocalDate, Int> = emptyMap()
        var finished = false
        var runningUntil = 0L

        DataStoreManager(context).update { s ->
            val from = if (s.focusAccountedUntilMs == 0L) now else s.focusAccountedUntilMs
            val ledger = focusMinutes(s.session, s.routines, from, now, zone)
            // The friends board compares these, so time with no blocking in place earns nothing.
            minutes = if (blocking && !s.strict.enforcementDisabled) ledger.minutesByDate else emptyMap()
            val ended = s.session != null && now >= s.session.endsAtMs && !strict.isHolding(s)
            finished = ended
            runningUntil = if (ended) 0L else s.session?.endsAtMs ?: 0L
            val next = s.copy(
                session = if (ended) null else s.session,
                unlocks = s.unlocks.filter { it.expiresAtMs > now },
                focusAccountedUntilMs = ledger.accountedUntilMs
            )
            if (ended) strict.disarmed(next) else next
        }

        val dao = AppDatabase.getInstance(context).dayTotalDao()
        minutes.forEach { (date, min) -> dao.addFocused(date.toString(), min) }
        if (finished) dao.addBlock(LocalDate.now(zone).toString())
        // Alarms do not survive a restart. Setting the same one again just replaces it.
        // A timer can outlive a date moved forward, and an alarm in the past would fire in a loop.
        if (runningUntil > now) scheduleEnd(context, runningUntil)
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        0,
        Intent(context, SessionAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun scheduleEnd(context: Context, atMs: Long) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarmIntent(context))
        } catch (_: SecurityException) {
            // Blocking does not depend on this alarm: decide() reads the clock.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMs, alarmIntent(context))
        }
    }

    fun cancelEnd(context: Context) {
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
    }
}
