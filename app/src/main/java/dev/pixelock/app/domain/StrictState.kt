package dev.pixelock.app.domain

/**
 * Strict Mode data. Read and written only through [dev.pixelock.app.domain.StrictRules] and
 * StrictModeController.
 *
 * Strict is tied to one timer session and one boot. When the timer ends, or the phone restarts,
 * nothing enforces it any more. That is what keeps the phone from ever being stuck.
 */
data class StrictState(
    /** User setting: new timers start strict. */
    val enabled: Boolean = false,
    val armedSessionId: String? = null,
    /** The timer the boot clock values below belong to. Every timer sets it, strict or not. */
    val clockSessionId: String? = null,
    val armedBootCount: Int = -1,
    val armedElapsedMs: Long = 0L,
    val enforcementDisabled: Boolean = false
)

/** Identity of the current boot. Elapsed time going backward also proves a restart. */
data class BootStamp(val bootCount: Int, val elapsedMs: Long)

object StrictRules {

    fun arm(state: StrictState, session: FocusSession, boot: BootStamp): StrictState =
        state.copy(
            armedSessionId = session.id.takeIf { state.enabled },
            clockSessionId = session.id,
            armedBootCount = boot.bootCount,
            armedElapsedMs = boot.elapsedMs
        )

    /** Strict switched on during a timer holds that timer for the time it has left. */
    fun armRunning(state: StrictState, session: FocusSession, boot: BootStamp, nowMs: Long): StrictState {
        val armed = state.copy(enabled = true, armedSessionId = session.id)
        // The boot clock is already timing this timer. Redoing it from the date would let the date in.
        if (state.clockSessionId == session.id && state.armedBootCount == boot.bootCount) return armed
        return armed.copy(
            clockSessionId = session.id,
            armedBootCount = boot.bootCount,
            armedElapsedMs = boot.elapsedMs - (nowMs - session.startedAtMs).coerceAtLeast(0L)
        )
    }

    fun isEnforcing(
        state: StrictState,
        session: FocusSession?,
        boot: BootStamp,
        failSafe: Boolean
    ): Boolean = session != null && state.armedSessionId == session.id && inBootWindow(state, session, boot, failSafe)

    /** Any timer, strict or not. Moving the date forward cannot end one of these early. */
    fun isHolding(
        state: StrictState,
        session: FocusSession?,
        boot: BootStamp,
        failSafe: Boolean
    ): Boolean = session != null && state.clockSessionId == session.id && inBootWindow(state, session, boot, failSafe)

    private fun inBootWindow(state: StrictState, session: FocusSession, boot: BootStamp, failSafe: Boolean): Boolean {
        if (failSafe || state.enforcementDisabled) return false
        if (state.armedBootCount != boot.bootCount || boot.elapsedMs < state.armedElapsedMs) return false
        // Timed on the boot clock, so changing the date can neither end nor stretch a timer.
        return boot.elapsedMs - state.armedElapsedMs < session.endsAtMs - session.startedAtMs
    }

    /** A confirmed payment ends the strict hold on the current timer. */
    fun disarm(state: StrictState): StrictState = state.copy(armedSessionId = null, clockSessionId = null)
}
