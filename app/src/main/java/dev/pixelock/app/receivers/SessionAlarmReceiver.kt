package dev.pixelock.app.receivers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.pixelock.app.R
import dev.pixelock.app.core.SessionManager
import dev.pixelock.app.ui.activity.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SessionAlarmReceiver : BroadcastReceiver() {

    private companion object {
        const val CHANNEL_ID = "session_end_channel"
        const val NOTIFICATION_ID = 4102
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                SessionManager.reconcile(context)
                notifyDone(context)
            } catch (_: Exception) {
            } finally {
                pending.finish()
            }
        }
    }

    private fun notifyDone(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.session_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(
            NOTIFICATION_ID,
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(context.getString(R.string.session_done_title))
                .setContentText(context.getString(R.string.session_done_text))
                .setSmallIcon(R.drawable.ic_stat_lock)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
    }
}
