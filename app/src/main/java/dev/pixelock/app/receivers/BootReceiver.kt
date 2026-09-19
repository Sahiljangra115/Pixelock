package dev.pixelock.app.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dev.pixelock.app.core.SessionManager
import dev.pixelock.app.services.ServiceWatchdogJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Brings back the watchdog after a reboot or update, and settles what ended while the phone was off. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ServiceWatchdogJob.schedule(context)
                SessionManager.reconcile(context)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }
}
