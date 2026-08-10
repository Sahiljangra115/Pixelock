package dev.pixelock.app.core

import android.content.Context
import java.io.File

/**
 * Crash loop breaker for the blocker process. After [MAX_CRASHES] crashes inside [WINDOW_MS] the
 * service goes inert until the user clears it, so a bad build can never trap the phone in a loop
 * of restarts and bounces. Uses plain files because SharedPreferences go stale across processes.
 */
object CrashGuard {
    private const val MAX_CRASHES = 3
    private const val WINDOW_MS = 2 * 60_000L

    private fun flag(context: Context) = File(context.filesDir, "failsafe")
    private fun times(context: Context) = File(context.filesDir, "crash_times")

    fun isFailSafe(context: Context): Boolean = flag(context).exists()

    fun recordServiceCrash(context: Context, nowMs: Long) {
        val file = times(context)
        val recent = (if (file.exists()) file.readLines() else emptyList())
            .mapNotNull { it.toLongOrNull() }
            .filter { nowMs - it < WINDOW_MS } + nowMs
        file.writeText(recent.joinToString("\n"))
        if (recent.size >= MAX_CRASHES) flag(context).createNewFile()
    }

    fun clear(context: Context) {
        flag(context).delete()
        times(context).delete()
    }
}
