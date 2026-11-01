package dev.pixelock.app.blockers

import dev.pixelock.app.R
import dev.pixelock.app.core.ServiceHealth
import dev.pixelock.app.domain.isRemovalPage
import dev.pixelock.app.hardcoded.StrictGuardTargets
import dev.pixelock.app.services.AppBlockerService

/**
 * Guards the screens that can remove this app. Strict Mode leaves any of them that shows this app's
 * name. The uninstall lock leaves only removal pages, so setup, Wi Fi, SIM and emergency pages work.
 */
class StrictGuard(private val service: AppBlockerService, private val policy: PolicyStore) {

    private val label by lazy { service.getString(R.string.app_name_plain) }

    /** Android prints these two of this app's own strings on the pages that switch off its powers. */
    private val removalWords by lazy {
        StrictGuardTargets.removalWords +
            service.getString(R.string.admin_desc) + service.getString(R.string.a11y_desc)
    }

    fun watches(pkg: String): Boolean =
        pkg in StrictGuardTargets.packages && (policy.strictEnforcing() || policy.uninstallLockOn())

    /** Android hides the admin switch page's text from this service, so its class is the sign. */
    fun onWindow(pkg: String, className: CharSequence?) {
        if (className == null || pkg !in StrictGuardTargets.packages) return
        if (StrictGuardTargets.removalClasses.none { className.contains(it) }) return
        // With no admin yet this is the prompt that turns the lock on, so it must stay open.
        if ((policy.strictEnforcing() || policy.uninstallLockOn()) && ServiceHealth.isAdminActive(service)) leave()
    }

    fun check(pkg: String) {
        if (!watches(pkg)) return
        // During a window change the root can still be the last app, even this one.
        val root = service.rootInActiveWindow?.takeIf { it.packageName == pkg } ?: return
        val screen = root.visibleTexts()
        val strict = policy.strictEnforcing() && screen.any { it.contains(label, ignoreCase = true) }
        // The system's own admin prompt looks like a removal page, and the lock needs that prompt.
        if (strict || (isLocked() && isRemovalPage(screen, label, removalWords))) leave()
    }

    private fun leave() {
        service.pressBack()
        service.pressHome()
    }

    private fun isLocked() = policy.uninstallLockOn() && ServiceHealth.isAdminActive(service)
}
