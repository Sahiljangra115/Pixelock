package dev.pixelock.app.blockers

import android.view.accessibility.AccessibilityNodeInfo
import dev.pixelock.app.domain.Decision
import dev.pixelock.app.domain.ForegroundState
import dev.pixelock.app.domain.Surface
import dev.pixelock.app.domain.decide
import dev.pixelock.app.hardcoded.ReelSelector
import dev.pixelock.app.hardcoded.ReelSelectors
import dev.pixelock.app.services.AppBlockerService
import java.time.ZonedDateTime
import java.util.concurrent.ConcurrentHashMap

/** Runs on the background worker. Uses the system node search, so it never walks a tree itself. */
class ReelGate(private val service: AppBlockerService, private val policy: PolicyStore) {

    private val versionCodes = ConcurrentHashMap<String, Long>()

    fun watches(pkg: String): Boolean = pkg in ReelSelectors.byPackage

    fun check(pkg: String) {
        val selector = ReelSelectors.byPackage[pkg] ?: return
        if (!policy.shortVideoBlockingOn()) return

        if (!isUntestedVersion(selector) && !isReelOnScreen(selector)) return
        val decision = decide(
            ForegroundState(pkg, Surface.SHORT_VIDEO), policy.snapshot(), ZonedDateTime.now()
        )
        if (decision is Decision.Block) service.block(pkg, decision, leaveWithBack = true)
    }

    private fun isUntestedVersion(selector: ReelSelector): Boolean {
        val max = selector.maxTestedVersionCode ?: return false
        val installed = versionCodes.getOrPut(selector.packageName) {
            runCatching { service.packageManager.getPackageInfo(selector.packageName, 0).longVersionCode }
                .getOrDefault(0L)
        }
        return installed > max
    }

    private fun isReelOnScreen(selector: ReelSelector): Boolean {
        val root = service.rootInActiveWindow ?: return false
        if (root.packageName?.toString() != selector.packageName) return false
        val minHeight = service.resources.displayMetrics.heightPixels * selector.minHeightFraction
        selector.accessibilityText?.let { text ->
            if (root.findAccessibilityNodeInfosByText(text).any { it.isShown(minHeight) }) return true
        }
        if (selector.requiredIds.isEmpty()) return false
        return selector.requiredIds.all { id ->
            root.findAccessibilityNodeInfosByViewId(id).any { it.isShown(minHeight) }
        }
    }

    private fun AccessibilityNodeInfo.isShown(minHeight: Float): Boolean {
        if (!isVisibleToUser) return false
        val bounds = android.graphics.Rect()
        getBoundsInScreen(bounds)
        return bounds.height() >= minHeight
    }
}
