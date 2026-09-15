package dev.pixelock.app.core

import android.content.Context
import android.os.SystemClock
import android.provider.Settings as AndroidSettings
import dev.pixelock.app.data.DataStoreManager
import dev.pixelock.app.data.models.Settings
import dev.pixelock.app.domain.BootStamp
import dev.pixelock.app.domain.FocusSession
import dev.pixelock.app.domain.StrictRules
import dev.pixelock.app.domain.isActive

/** The only class that reads or writes Strict Mode state. */
class StrictModeController(context: Context) {

    private val app = context.applicationContext
    private val store = DataStoreManager(app)

    private fun bootStamp() = BootStamp(
        bootCount = AndroidSettings.Global.getInt(
            app.contentResolver, AndroidSettings.Global.BOOT_COUNT, 0
        ),
        elapsedMs = SystemClock.elapsedRealtime()
    )

    fun isEnforcing(settings: Settings): Boolean =
        StrictRules.isEnforcing(settings.strict, settings.session, bootStamp(), CrashGuard.isFailSafe(app))

    /** The boot clock says this timer has time left, whatever the date says. */
    fun isHolding(settings: Settings): Boolean =
        StrictRules.isHolding(settings.strict, settings.session, bootStamp(), CrashGuard.isFailSafe(app))

    fun isTimerRunning(settings: Settings): Boolean =
        settings.session?.isActive(System.currentTimeMillis()) == true || isHolding(settings)

    fun isEnabled(settings: Settings): Boolean = settings.strict.enabled

    fun isEnforcementDisabled(settings: Settings): Boolean = settings.strict.enforcementDisabled

    /** Cannot be switched off while any timer runs. Switched on, it holds the running timer too. */
    suspend fun setEnabled(enabled: Boolean) {
        store.update { s ->
            val running = s.session?.takeIf { isTimerRunning(s) }
            when {
                running == null -> s.copy(strict = s.strict.copy(enabled = enabled))
                enabled -> s.copy(
                    strict = StrictRules.armRunning(s.strict, running, bootStamp(), System.currentTimeMillis())
                )
                else -> s
            }
        }
    }

    fun armed(settings: Settings, session: FocusSession): Settings =
        settings.copy(session = session, strict = StrictRules.arm(settings.strict, session, bootStamp()))

    fun disarmed(settings: Settings): Settings =
        settings.copy(strict = StrictRules.disarm(settings.strict))

    /** No timer, no hold, and nothing blocks until the switch is turned off again. */
    fun killed(settings: Settings): Settings {
        val next = disarmed(settings)
        return next.copy(session = null, strict = next.strict.copy(enforcementDisabled = true))
    }

    suspend fun setEnforcementDisabled(disabled: Boolean) {
        store.update { it.copy(strict = it.strict.copy(enforcementDisabled = disabled)) }
    }
}
