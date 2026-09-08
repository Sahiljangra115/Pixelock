package dev.pixelock.app.blockers

import dev.pixelock.app.domain.Decision
import dev.pixelock.app.domain.ForegroundState
import dev.pixelock.app.domain.decide
import dev.pixelock.app.services.AppBlockerService
import java.time.ZonedDateTime

/** Runs on the main thread. Checks the foreground app when it changes and on each usage tick. */
class AppGate(private val service: AppBlockerService, private val policy: PolicyStore) {

    private var foreground = ""

    fun onForeground(pkg: String) {
        foreground = pkg
        evaluate(pkg)
    }

    fun recheck() {
        if (foreground.isNotEmpty()) evaluate(foreground)
    }

    private fun evaluate(pkg: String) {
        val decision = decide(ForegroundState(pkg), policy.snapshot(), ZonedDateTime.now())
        if (decision is Decision.Block) service.block(pkg, decision, leaveWithBack = false)
    }
}
