package dev.pixelock.app.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import dev.pixelock.app.R
import dev.pixelock.app.core.CrashGuard
import dev.pixelock.app.core.ServiceHealth
import dev.pixelock.app.core.SessionManager
import dev.pixelock.app.data.DataStoreManager
import dev.pixelock.app.ui.activity.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

/** Wakes every 15 minutes, survives reboot, and tells the user when blocking has stopped. */
class ServiceWatchdogJob : JobService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            try {
                SessionManager.reconcile(applicationContext)
                val ready = DataStoreManager(applicationContext).settings.first().onboardingDone
                val running = ServiceHealth.isBlockerRunning(applicationContext)
                val paused = CrashGuard.isFailSafe(applicationContext)
                if (ready && (!running || paused)) showDown(applicationContext) else clearDown(applicationContext)
            } catch (_: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 7711
        private const val CHANNEL_ID = "service_down_channel"
        private const val NOTIFICATION_ID = 7712

        fun schedule(context: Context) {
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, ServiceWatchdogJob::class.java))
                .setPersisted(true)
                .setPeriodic(TimeUnit.MINUTES.toMillis(15))
                .build()
            context.getSystemService(JobScheduler::class.java).schedule(job)
        }

        private fun showDown(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.down_channel),
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
            val open = PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            manager.notify(
                NOTIFICATION_ID,
                NotificationCompat.Builder(context, CHANNEL_ID)
                    .setContentTitle(context.getString(R.string.down_title))
                    .setContentText(context.getString(R.string.down_text))
                    .setSmallIcon(R.drawable.ic_stat_lock)
                    .setContentIntent(open)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .build()
            )
        }

        private fun clearDown(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        }
    }
}
