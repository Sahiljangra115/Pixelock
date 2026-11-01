package dev.pixelock.app.blockers

import dev.pixelock.app.domain.Decision
import dev.pixelock.app.domain.ForegroundState
import dev.pixelock.app.domain.Surface
import dev.pixelock.app.domain.bannedOnInstallPage
import dev.pixelock.app.domain.decide
import dev.pixelock.app.hardcoded.InstallBlockCatalog
import dev.pixelock.app.services.AppBlockerService
import java.time.ZonedDateTime

/**
 * Leaves a store or installer page that offers a banned app. Android gives no real way to refuse an
 * install without device owner, so an app that gets on anyway is closed at open by decide().
 */
class InstallGuard(private val service: AppBlockerService, private val policy: PolicyStore) {

    fun watches(pkg: String): Boolean = pkg in InstallBlockCatalog.installPages && policy.bannedApps().isNotEmpty()

    /** Worker only. Home, not back: a search page would bounce back here in a loop. */
    fun check(pkg: String) {
        if (!watches(pkg)) return
        val root = service.rootInActiveWindow?.takeIf { it.packageName == pkg } ?: return
        val installer = pkg in InstallBlockCatalog.installers
        val app = bannedOnInstallPage(root.visibleTexts(), policy.bannedApps(), installer) ?: return
        val decision = decide(
            ForegroundState(app.packages.firstOrNull() ?: app.name, Surface.INSTALL_PAGE),
            policy.snapshot(),
            ZonedDateTime.now()
        )
        if (decision is Decision.Block) service.block(pkg, decision, leaveWithBack = false)
    }
}
