package dev.pixelock.app.domain

/** A running focus timer. */
data class FocusSession(
    val id: String = "",
    val startedAtMs: Long = 0L,
    val endsAtMs: Long = 0L,
    /** The timer's apps are open until this time. Blocking comes back by itself after it. */
    val pausedUntilMs: Long = 0L,
    val pausesUsed: Int = 0
)

/** A weekly slot. Days use 0 = Sunday. A slot may run past midnight. Start == end means off. */
data class Routine(
    val id: Int = 0,
    val enabled: Boolean = false,
    val days: Set<Int> = setOf(0, 1, 2, 3, 4, 5, 6),
    val startMin: Int = 0,
    val endMin: Int = 60,
    val locked: Boolean = false
)

enum class BagMode { WINDOW, DAILY_LIMIT }

/** A named app set. Its apps are allowed only inside its window, or until its daily limit is used. */
data class Bag(
    val id: Int = 0,
    val packages: List<String> = emptyList(),
    val mode: BagMode = BagMode.DAILY_LIMIT,
    val startMin: Int = 13 * 60,
    val endMin: Int = 14 * 60,
    val dailyLimitMin: Int = 30,
    val locked: Boolean = false
)

/** A paid, time boxed pass for one app. */
data class Unlock(
    val packageName: String = "",
    val expiresAtMs: Long = 0L
)

/** An order being paid for. [packageName] is empty when paying to end the timer. */
data class PendingOrder(
    val id: String = "",
    val purpose: String = "",
    val packageName: String = "",
    val url: String = "",
    val createdAtMs: Long = 0L
)

/** Shorter than the 20 minutes a payment link lives, so a reused link still works when opened. */
const val ORDER_REUSE_MS = 15 * 60_000L

/** The same page again, so tapping twice cannot end in paying twice. */
fun PendingOrder.canReuse(purpose: String, packageName: String, nowMs: Long): Boolean =
    this.purpose == purpose && this.packageName == packageName &&
        url.isNotEmpty() && nowMs - createdAtMs in 0 until ORDER_REUSE_MS

data class PolicyState(
    val session: FocusSession? = null,
    val routines: List<Routine> = emptyList(),
    val bags: List<Bag> = emptyList(),
    val focusBlockList: Set<String> = emptySet(),
    val shortVideoBlockingOn: Boolean = false,
    /** Kept off this phone. Closed at open, even if installed. */
    val bannedPackages: Set<String> = emptySet(),
    val unlocks: List<Unlock> = emptyList(),
    /** Checked before every rule. Static table plus the phone's own dialer, SMS, launcher, keyboard. */
    val alwaysAllow: Set<String> = emptySet(),
    /** Foreground minutes used today, per package. */
    val usedMinToday: Map<String, Int> = emptyMap(),
    /** The boot clock says the timer has time left, whatever the date says. */
    val timerHolding: Boolean = false,
    /** Hidden developer kill switch. */
    val enforcementDisabled: Boolean = false,
    /** Set by the crash loop breaker. */
    val failSafe: Boolean = false
)

const val MINUTES_PER_DAY = 1440

/** Longest custom timer. Nothing the user sets can lock the phone for longer than this. */
const val MAX_SESSION_MIN = 12 * 60

/** A pause must be easy to take, so these two numbers are what stop it from ending the timer. */
const val PAUSE_MIN = 5
const val MAX_PAUSES = 3

const val ADD_TIME_MIN = 15
