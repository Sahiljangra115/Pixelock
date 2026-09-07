package dev.pixelock.app.services

import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import dev.pixelock.app.CrashLogger
import dev.pixelock.app.blockers.AppGate
import dev.pixelock.app.blockers.PolicyStore
import dev.pixelock.app.blockers.ReelGate
import dev.pixelock.app.blockers.ShadeLock
import dev.pixelock.app.blockers.StrictGuard
import dev.pixelock.app.core.DecisionLog
import dev.pixelock.app.core.SessionManager
import dev.pixelock.app.domain.Decision
import dev.pixelock.app.trackers.AppUsageTracker
import dev.pixelock.app.ui.activity.BlockActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Hosts every blocker. Must never crash because one feature fails: each feature call stays inside
 * a try block here. Only package names cross to the worker, so no event copy ever needs recycling.
 */
class AppBlockerService : BaseBlockingService() {

    private companion object {
        const val SYSTEM_UI = "com.android.systemui"
        const val REEL_BLOCK_GAP_MS = 3_000L
        const val APP_BLOCK_GAP_MS = 1_000L
        const val WINDOW_RETRY_MS = 250L
        const val MAX_WINDOW_RETRIES = 8
        const val WORK_GAP_MS = 250L
        const val BASE_EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        const val WIDE_EVENTS = BASE_EVENTS or
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
            AccessibilityEvent.TYPE_VIEW_SCROLLED
    }

    private val policy = PolicyStore()
    private val appGate = AppGate(this, policy)
    private val reelGate = ReelGate(this, policy)
    private val strictGuard = StrictGuard(this, policy)
    private val shadeLock = ShadeLock(this, policy)
    private val usageTracker by lazy {
        AppUsageTracker(this, policy::isDistraction) { guarded("usage tick") { appGate.recheck() } }
    }

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val workChannel = Channel<String>(Channel.CONFLATED)
    private val main = Handler(Looper.getMainLooper())
    private val crashLogger by lazy { CrashLogger(this) }

    private var foreground = ""
    private var wideEvents = false
    private var connected = false
    private var windowRetries = 0

    override fun onServiceConnected() {
        super.onServiceConnected()
        usageTracker.setup()
        policy.setup(this, usageTracker::usedMinutes) { guarded("policy change") { onPolicyChanged() } }
        connected = true
        scope.launch {
            for (pkg in workChannel) {
                try {
                    reelGate.check(pkg)
                    strictGuard.check(pkg)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    crashLogger.logNonFatalError(Exception(t))
                }
                // The channel keeps the newest event, so the end of a burst is still looked at.
                delay(WORK_GAP_MS)
            }
        }
        scope.launch(Dispatchers.IO) {
            runCatching { SessionManager.reconcile(this@AppBlockerService) }
            runCatching { ServiceWatchdogJob.schedule(this@AppBlockerService) }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        if (!connected || policy.failSafe) return
        val pkg = event.packageName?.toString() ?: return

        guarded("shade lock") { shadeLock.onEvent(event) }

        // Keyboards and SystemUI draw over the real app, so they never count as foreground.
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            pkg != SYSTEM_UI && !policy.isKeyboard(pkg) && pkg != foreground
        ) {
            foreground = pkg
            guarded("foreground") { onForegroundChanged(pkg) }
        }

        if (reelGate.watches(pkg) || strictGuard.watches(pkg)) workChannel.trySend(pkg)
    }

    private fun onForegroundChanged(pkg: String) {
        windowRetries = 0
        setWideEvents(reelGate.watches(pkg) || strictGuard.watches(pkg))
        usageTracker.onForeground(pkg)
        appGate.onForeground(pkg)
    }

    private fun onPolicyChanged() {
        if (policy.failSafe) {
            setWideEvents(false)
            return
        }
        setWideEvents(reelGate.watches(foreground) || strictGuard.watches(foreground))
        appGate.recheck()
    }

    /** Content events are only wanted inside watched apps. Everywhere else stays quiet. */
    private fun setWideEvents(wide: Boolean) {
        if (wide == wideEvents) return
        wideEvents = wide
        serviceInfo = serviceInfo?.apply { eventTypes = if (wide) WIDE_EVENTS else BASE_EVENTS }
    }

    fun isInCall(): Boolean = getSystemService(AudioManager::class.java).mode in
        setOf(AudioManager.MODE_IN_CALL, AudioManager.MODE_IN_COMMUNICATION, AudioManager.MODE_RINGTONE)

    /** Thread safe. Called from the main thread and from the worker. */
    fun block(pkg: String, decision: Decision.Block, leaveWithBack: Boolean) {
        val gap = if (leaveWithBack) REEL_BLOCK_GAP_MS else APP_BLOCK_GAP_MS
        if (!isDelayOver(gap)) {
            // The app stays foreground, so no new event comes. Look again once the gap has passed.
            if (!leaveWithBack) main.postDelayed({ guarded("retry") { appGate.recheck() } }, gap)
            return
        }
        if (isInCall()) return
        val active = rootInActiveWindow?.packageName?.toString()
        if (active != null && active != pkg) {
            // An app's first event can beat its window. A floating window, which is not really in
            // front, just runs out of tries here and the usage tick looks again later.
            if (!leaveWithBack && windowRetries++ < MAX_WINDOW_RETRIES) {
                main.postDelayed({ guarded("retry") { appGate.recheck() } }, WINDOW_RETRY_MS)
            }
            return
        }

        DecisionLog.add(this, "block $pkg ${decision.reason}")
        if (leaveWithBack) pressBack() else pressHome()
        main.postDelayed({
            guarded("block screen") {
                startActivity(BlockActivity.intent(this, pkg, decision, leaveWithBack))
            }
        }, 120)
    }

    private inline fun guarded(name: String, action: () -> Unit) {
        try {
            action()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            crashLogger.logNonFatalError(Exception(name, t))
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connected = false
        guarded("worker") { scope.cancel() }
        guarded("channel") { workChannel.cancel() }
        guarded("policy") { policy.onDestroy() }
        guarded("usage") { usageTracker.onDestroy() }
        guarded("handler") { main.removeCallbacksAndMessages(null) }
    }
}
