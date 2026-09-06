package dev.pixelock.app.services

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import dev.pixelock.app.R
import dev.pixelock.app.data.DataStoreManager

@SuppressLint("AccessibilityPolicy")
open class BaseBlockingService : AccessibilityService() {

    val dataStoreManager by lazy { DataStoreManager(this) }

    private var lastActionAt = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        // Only helps the service live longer. Blocking still works if the system refuses it.
        runCatching { startForegroundNotification() }
    }

    private fun startForegroundNotification() {
        val channelId = "blocking_service_channel"
        val channel = NotificationChannel(
            channelId,
            getString(R.string.service_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.service_title))
            .setContentText(getString(R.string.service_text))
            .setSmallIcon(R.drawable.ic_stat_lock)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    fun isDelayOver(delayMs: Long): Boolean = SystemClock.uptimeMillis() - lastActionAt > delayMs

    fun pressHome() {
        performGlobalAction(GLOBAL_ACTION_HOME)
        lastActionAt = SystemClock.uptimeMillis()
    }

    fun pressBack() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        lastActionAt = SystemClock.uptimeMillis()
    }

    private companion object {
        const val NOTIFICATION_ID = 4101
    }
}
