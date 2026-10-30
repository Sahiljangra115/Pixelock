package dev.pixelock.app.services

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import dev.pixelock.app.data.DataStoreManager

/**
 * The system keeps this bound, so it needs no foreground service. Do not add one: it puts a Stop
 * button in Active apps, and Stop leaves the service marked crashed, with nothing blocked until
 * Accessibility is toggled, the app updates, or the phone restarts. That would also end a timer.
 */
@SuppressLint("AccessibilityPolicy")
open class BaseBlockingService : AccessibilityService() {

    val dataStoreManager by lazy { DataStoreManager(this) }

    private var lastActionAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun isDelayOver(delayMs: Long): Boolean = SystemClock.uptimeMillis() - lastActionAt > delayMs

    fun pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        lastActionAt = SystemClock.uptimeMillis()
    }

    fun pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        lastActionAt = SystemClock.uptimeMillis()
    }
}
