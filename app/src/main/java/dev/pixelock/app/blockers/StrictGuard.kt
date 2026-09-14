package dev.pixelock.app.blockers

import android.view.accessibility.AccessibilityNodeInfo
import dev.pixelock.app.R
import dev.pixelock.app.hardcoded.StrictGuardTargets
import dev.pixelock.app.services.AppBlockerService

/**
 * While Strict Mode holds, leaves any settings or uninstall screen that shows this app's name.
 * Other settings pages stay open, so Wi Fi, SIM and emergency pages keep working.
 */
class StrictGuard(private val service: AppBlockerService, private val policy: PolicyStore) {

    private companion object {
        const val MAX_NODES = 500
    }

    private val label by lazy { service.getString(R.string.app_name_plain) }

    fun watches(pkg: String): Boolean = pkg in StrictGuardTargets.packages && policy.strictEnforcing()

    fun check(pkg: String) {
        if (pkg !in StrictGuardTargets.packages || !policy.strictEnforcing()) return
        // During a window change the root can still be the last app, even this one.
        val root = service.rootInActiveWindow?.takeIf { it.packageName == pkg } ?: return
        if (root.findAccessibilityNodeInfosByText(label).any { it.isVisibleToUser } || shows(root)) {
            service.pressBack()
            service.pressHome()
        }
    }

    /** Compose pages, such as app info on newer Android, answer no system search. Worker only. */
    private fun shows(root: AccessibilityNodeInfo): Boolean {
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        pending.addLast(root)
        var seen = 0
        while (pending.isNotEmpty() && seen++ < MAX_NODES) {
            val node = pending.removeFirst()
            if (node.isVisibleToUser && node.text?.contains(label, ignoreCase = true) == true) return true
            for (i in 0 until node.childCount) node.getChild(i)?.let(pending::addLast)
        }
        return false
    }
}
