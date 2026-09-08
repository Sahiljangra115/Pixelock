package dev.pixelock.app.blockers

import android.os.Handler
import android.os.Looper
import dev.pixelock.app.core.CrashGuard
import dev.pixelock.app.core.DeviceAllowList
import dev.pixelock.app.core.StrictModeController
import dev.pixelock.app.data.models.Settings
import dev.pixelock.app.domain.PolicyState
import dev.pixelock.app.domain.isActive
import dev.pixelock.app.domain.isPaused
import dev.pixelock.app.services.BaseBlockingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** In memory copy of the rules. Filled from the settings flow, never read from disk per event. */
class PolicyStore {

    @Volatile private var settings = Settings()
    @Volatile private var base = PolicyState()
    @Volatile private var distractions: Set<String> = emptySet()
    @Volatile private var keyboards: Set<String> = emptySet()
    @Volatile private var usedMinutes: (String) -> Int = { 0 }
    @Volatile var failSafe = false
        private set

    private var job: Job? = null
    private val main = Handler(Looper.getMainLooper())
    private lateinit var strict: StrictModeController

    fun setup(service: BaseBlockingService, usedMinutes: (String) -> Int, onChanged: () -> Unit) {
        this.usedMinutes = usedMinutes
        strict = StrictModeController(service)
        failSafe = CrashGuard.isFailSafe(service)
        job = CoroutineScope(Dispatchers.IO).launch {
            service.dataStoreManager.settings.collect { s ->
                settings = s
                failSafe = CrashGuard.isFailSafe(service)
                val bagApps = s.bags.flatMap { it.packages }
                keyboards = DeviceAllowList.keyboards(service)
                distractions = (s.focusBlockList + bagApps).toSet()
                base = PolicyState(
                    session = s.session,
                    routines = s.routines,
                    bags = s.bags,
                    focusBlockList = s.focusBlockList.toSet(),
                    shortVideoBlockingOn = s.shortVideoBlockingOn,
                    unlocks = s.unlocks,
                    alwaysAllow = DeviceAllowList.resolve(service),
                    enforcementDisabled = strict.isEnforcementDisabled(s),
                    failSafe = failSafe
                )
                main.post(onChanged)
            }
        }
    }

    fun snapshot(): PolicyState {
        val policy = base
        val used = usedMinutes
        return policy.copy(
            usedMinToday = policy.bags.flatMap { it.packages }.associateWith(used),
            timerHolding = strict.isHolding(settings),
            failSafe = failSafe
        )
    }

    fun isDistraction(pkg: String): Boolean = pkg in distractions

    fun isKeyboard(pkg: String): Boolean = pkg in keyboards

    fun shortVideoBlockingOn(): Boolean = base.shortVideoBlockingOn

    fun sessionActive(nowMs: Long): Boolean {
        val session = base.session ?: return false
        if (session.isPaused(nowMs)) return false
        return session.isActive(nowMs) || strict.isHolding(settings)
    }

    fun shadeLockOn(): Boolean = settings.shadeLockOn

    fun strictEnforcing(): Boolean = strict.isEnforcing(settings)

    fun onDestroy() {
        job?.cancel()
        main.removeCallbacksAndMessages(null)
    }
}
