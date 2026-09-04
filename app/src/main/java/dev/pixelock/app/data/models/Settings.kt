package dev.pixelock.app.data.models

import dev.pixelock.app.domain.Bag
import dev.pixelock.app.domain.FocusSession
import dev.pixelock.app.domain.PendingOrder
import dev.pixelock.app.domain.Routine
import dev.pixelock.app.domain.StrictState
import dev.pixelock.app.domain.Unlock
import dev.pixelock.app.hardcoded.DefaultBlockList

const val THEME_SYSTEM = 0
const val THEME_LIGHT = 1
const val THEME_DARK = 2

/** Stored as JSON. Every field has a default so older files still load. Do not rename fields. */
data class Settings(
    val session: FocusSession? = null,
    val focusBlockList: List<String> = DefaultBlockList.packages,
    val bags: List<Bag> = listOf(Bag(id = 0), Bag(id = 1), Bag(id = 2)),
    val routines: List<Routine> = listOf(Routine(id = 0), Routine(id = 1), Routine(id = 2)),
    val shortVideoBlockingOn: Boolean = true,
    val shadeLockOn: Boolean = false,
    val strict: StrictState = StrictState(),
    val unlocks: List<Unlock> = emptyList(),
    val themeMode: Int = THEME_SYSTEM,
    val onboardingDone: Boolean = false,
    val focusAccountedUntilMs: Long = 0L,
    /** Bumped so the service rereads the crash breaker file. */
    val failSafeRev: Int = 0,
    /** Shown to friends instead of any real name. */
    val alias: String = "",
    val groups: List<String> = emptyList(),
    /** First date that still has to go up. Empty means the first upload has not run yet. */
    val syncFrom: String = "",
    /** Shared with the laptop script so a scanned total can be trusted. */
    val laptopKey: String = "",
    /** Kept until each unlock is granted, so a payment survives the app being killed. */
    val pendingOrders: List<PendingOrder> = emptyList(),
    /** Empty until the first timer asks for a password. */
    val endPasswordSalt: String = "",
    val endPasswordHash: String = ""
)
