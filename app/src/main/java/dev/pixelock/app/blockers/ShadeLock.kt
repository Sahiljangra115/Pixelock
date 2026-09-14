package dev.pixelock.app.blockers

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.media.AudioManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.pixelock.app.services.AppBlockerService

/**
 * Closes the notification shade during a session when the setting is on. Reacts to the SystemUI
 * window event only, never polls. Leaves the shade alone on the lock screen and during calls.
 */
class ShadeLock(private val service: AppBlockerService, private val policy: PolicyStore) {

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        const val MIN_GAP_MS = 400L
    }

    private var lastDismiss = 0L

    fun onEvent(event: AccessibilityEvent) {
        if (event.packageName?.toString() != SYSTEM_UI) return
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        if (!policy.shadeLockOn() || policy.failSafe) return
        if (policy.snapshot().enforcementDisabled) return
        if (!policy.sessionActive(System.currentTimeMillis())) return

        val now = SystemClock.uptimeMillis()
        if (now - lastDismiss < MIN_GAP_MS) return
        if (service.getSystemService(KeyguardManager::class.java).isKeyguardLocked) return
        if (service.isInCall()) return

        lastDismiss = now
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
    }
}
