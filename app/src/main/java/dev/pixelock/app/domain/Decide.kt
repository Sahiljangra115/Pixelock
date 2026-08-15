package dev.pixelock.app.domain

import java.time.ZonedDateTime

enum class Surface { APP, SHORT_VIDEO }

data class ForegroundState(val packageName: String, val surface: Surface = Surface.APP)

enum class BlockReason { SHORT_VIDEO, SESSION, ROUTINE, BAG }

sealed interface Decision {
    data object Allow : Decision
    /** [untilMs] is 0 when no end time is known. */
    data class Block(val reason: BlockReason, val untilMs: Long = 0L) : Decision
}

/** The single place blocking rules live. Everything that blocks calls this. */
fun decide(foreground: ForegroundState, policy: PolicyState, now: ZonedDateTime): Decision {
    val pkg = foreground.packageName
    if (pkg.isEmpty() || pkg in policy.alwaysAllow) return Decision.Allow
    if (policy.enforcementDisabled || policy.failSafe) return Decision.Allow

    // Before unlocks: a paid pass opens an app, never its short video feed.
    if (foreground.surface == Surface.SHORT_VIDEO && policy.shortVideoBlockingOn) {
        return Decision.Block(BlockReason.SHORT_VIDEO)
    }

    val nowMs = now.toInstant().toEpochMilli()
    if (policy.unlocks.any { it.packageName == pkg && it.expiresAtMs > nowMs }) return Decision.Allow

    val inBag = policy.bags.firstOrNull { pkg in it.packages }
    val isDistraction = pkg in policy.focusBlockList || inBag != null

    if (isDistraction) {
        policy.session?.takeIf { (it.isActive(nowMs) || policy.timerHolding) && !it.isPaused(nowMs) }?.let {
            return Decision.Block(BlockReason.SESSION, it.endsAtMs)
        }
        val routineEnd = policy.routines.mapNotNull { it.activeEndMs(now) }.maxOrNull()
        if (routineEnd != null) return Decision.Block(BlockReason.ROUTINE, routineEnd)
    }

    if (inBag != null && !inBag.isOpen(now, policy.usedMinToday)) {
        return Decision.Block(BlockReason.BAG, inBag.nextOpenMs(now))
    }
    return Decision.Allow
}

fun FocusSession.isActive(nowMs: Long): Boolean = nowMs in startedAtMs until endsAtMs

/** The upper bound stops a date moved back from stretching a pause by more than one go. */
// ponytail: wall clock. Moving the date back again and again during a pause keeps it open, boot clock it if that matters
fun FocusSession.isPaused(nowMs: Long): Boolean = pausedUntilMs - nowMs in 1..PAUSE_MIN * 60_000L

/** Null when a pause is running or none are left. The end moves out, so no focus time is lost. */
fun FocusSession.paused(nowMs: Long): FocusSession? {
    if (isPaused(nowMs) || pausesUsed >= MAX_PAUSES) return null
    val pause = PAUSE_MIN * 60_000L
    return copy(pausedUntilMs = nowMs + pause, endsAtMs = endsAtMs + pause, pausesUsed = pausesUsed + 1)
}

/** Gives back the part of the pause that was not used. */
fun FocusSession.resumed(nowMs: Long): FocusSession? =
    if (isPaused(nowMs)) copy(pausedUntilMs = 0L, endsAtMs = endsAtMs - (pausedUntilMs - nowMs)) else null

/** Null when the timer would pass [MAX_SESSION_MIN]. */
fun FocusSession.withAddedTime(): FocusSession? =
    copy(endsAtMs = endsAtMs + ADD_TIME_MIN * 60_000L)
        .takeIf { it.endsAtMs - it.startedAtMs <= MAX_SESSION_MIN * 60_000L }

/** End of the window this routine is in right now, or null when it is not running. */
fun Routine.activeEndMs(now: ZonedDateTime): Long? {
    if (!enabled || startMin == endMin) return null
    val minute = now.hour * 60 + now.minute
    val today = now.dayOfWeek.value % 7
    val yesterday = (today + 6) % 7
    val dayStart = now.toLocalDate().atStartOfDay(now.zone)

    if (startMin < endMin) {
        if (today in days && minute >= startMin && minute < endMin) {
            return dayStart.plusMinutes(endMin.toLong()).toInstant().toEpochMilli()
        }
        return null
    }
    if (today in days && minute >= startMin) {
        return dayStart.plusDays(1).plusMinutes(endMin.toLong()).toInstant().toEpochMilli()
    }
    if (yesterday in days && minute < endMin) {
        return dayStart.plusMinutes(endMin.toLong()).toInstant().toEpochMilli()
    }
    return null
}

fun Bag.isOpen(now: ZonedDateTime, usedMinToday: Map<String, Int>): Boolean = when (mode) {
    BagMode.DAILY_LIMIT -> packages.sumOf { usedMinToday[it] ?: 0 } < dailyLimitMin
    BagMode.WINDOW -> {
        val minute = now.hour * 60 + now.minute
        when {
            startMin == endMin -> false
            startMin < endMin -> minute in startMin until endMin
            else -> minute >= startMin || minute < endMin
        }
    }
}

/** When a closed bag opens again: its next window, or midnight for a used up limit. */
private fun Bag.nextOpenMs(now: ZonedDateTime): Long {
    val dayStart = now.toLocalDate().atStartOfDay(now.zone)
    if (mode == BagMode.DAILY_LIMIT || startMin == endMin) {
        return dayStart.plusDays(1).toInstant().toEpochMilli()
    }
    val minute = now.hour * 60 + now.minute
    val next = if (minute < startMin) dayStart else dayStart.plusDays(1)
    return next.plusMinutes(startMin.toLong()).toInstant().toEpochMilli()
}

/** Edits are refused for a locked item during a session, and for every item under Strict Mode. */
fun canEdit(locked: Boolean, sessionActive: Boolean, strictEnforcing: Boolean): Boolean =
    !strictEnforcing && !(locked && sessionActive)
