package dev.pixelock.app.core

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import dev.pixelock.app.hardcoded.OemAutostartIntents
import dev.pixelock.app.receivers.AdminReceiver
import dev.pixelock.app.services.AppBlockerService

/** What the phone has granted, and the screens that grant it. */
object ServiceHealth {

    fun adminComponent(context: Context) = ComponentName(context, AdminReceiver::class.java)

    /** True only when the service is actually bound, which a saved setting cannot prove. */
    fun isBlockerRunning(context: Context): Boolean {
        val id = ComponentName(context, AppBlockerService::class.java).flattenToShortString()
        return context.getSystemService(AccessibilityManager::class.java)
            .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id == id }
    }

    fun isAdminActive(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java).isAdminActive(adminComponent(context))

    fun removeAdmin(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm.isAdminActive(adminComponent(context))) dpm.removeActiveAdmin(adminComponent(context))
    }

    fun adminIntent(context: Context): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComponent(context))

    fun isBatteryFree(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            .isIgnoringBatteryOptimizations(context.packageName)

    /** The one tap request, or the list once it is on, because the request then shows nothing. */
    fun batteryIntent(context: Context): Intent =
        if (isBatteryFree(context)) {
            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        } else {
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, packageUri(context))
        }

    fun notificationIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, packageUri(context))

    fun autostartIntent(context: Context): Intent? =
        OemAutostartIntents.candidates.asSequence()
            .map { (pkg, cls) -> Intent().setComponent(ComponentName(pkg, cls)) }
            .firstOrNull { context.packageManager.resolveActivity(it, 0) != null }

    private fun packageUri(context: Context) = Uri.parse("package:${context.packageName}")
}
