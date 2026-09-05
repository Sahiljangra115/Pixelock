package dev.pixelock.app.core

import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import dev.pixelock.app.hardcoded.AlwaysAllow

/** Adds this phone's own dialer, SMS app, launchers and keyboards to the static table. */
object DeviceAllowList {

    fun resolve(context: Context): Set<String> {
        val result = AlwaysAllow.packages.toMutableSet()
        result += context.packageName
        runCatching { context.getSystemService(TelecomManager::class.java).defaultDialerPackage }
            .getOrNull()?.let { result += it }
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }
            .getOrNull()?.let { result += it }
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.queryIntentActivities(home, 0)
                .forEach { result += it.activityInfo.packageName }
        }
        result += keyboards(context)
        return result
    }

    fun keyboards(context: Context): Set<String> = runCatching {
        context.getSystemService(InputMethodManager::class.java).enabledInputMethodList
            .map { it.packageName }.toSet()
    }.getOrDefault(emptySet())
}
