package dev.pixelock.app.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Longest gap that is counted, so a clock jump cannot add months of fake focus. */
const val MAX_LEDGER_SPAN_MS = 7L * 24 * 60 * 60 * 1000

data class LedgerResult(val minutesByDate: Map<LocalDate, Int>, val accountedUntilMs: Long)

/**
 * Counts whole minutes in [fromMs, toMs) during which a session or routine was running.
 * Only whole minutes are consumed, so the remainder is picked up by the next call.
 */
// ponytail: minute scan, bounded to 10k steps by MAX_LEDGER_SPAN_MS, runs off the main thread
fun focusMinutes(
    session: FocusSession?,
    routines: List<Routine>,
    fromMs: Long,
    toMs: Long,
    zone: ZoneId
): LedgerResult {
    if (toMs <= fromMs) return LedgerResult(emptyMap(), fromMs)
    val start = maxOf(fromMs, toMs - MAX_LEDGER_SPAN_MS)
    val minutes = (toMs - start) / 60_000L
    val result = HashMap<LocalDate, Int>()
    for (i in 0 until minutes) {
        val ms = start + i * 60_000L
        val at = Instant.ofEpochMilli(ms).atZone(zone)
        val running = session?.takeIf { it.isActive(ms) && !it.isPaused(ms) } != null ||
            routines.any { it.activeEndMs(at) != null }
        if (running) result.merge(at.toLocalDate(), 1, Int::plus)
    }
    return LedgerResult(result, start + minutes * 60_000L)
}
