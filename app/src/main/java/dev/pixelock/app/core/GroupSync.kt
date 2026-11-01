package dev.pixelock.app.core

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import dev.pixelock.app.data.DataStoreManager
import dev.pixelock.app.data.db.AppDatabase
import dev.pixelock.app.data.remote.Backends
import dev.pixelock.app.domain.LaptopDay
import dev.pixelock.app.domain.dayUpload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Groups and the upload of daily totals. Runs in the main process only: no network in blockers. */
object GroupSync {

    /** Enough history for a month board on the day someone joins. */
    private const val BACKFILL_DAYS = 31L
    private const val JOB_ID = 7713

    /** Returns the new group's code. */
    suspend fun create(context: Context, alias: String, name: String): String = withContext(Dispatchers.IO) {
        val code = Backends.get(context).createGroup(alias, name)
        added(context, code, alias, name)
        code
    }

    suspend fun join(context: Context, code: String, alias: String) = withContext(Dispatchers.IO) {
        val backend = Backends.get(context)
        backend.joinGroup(code, alias)
        // Only a label, so a failed read must not undo the join.
        added(context, code, alias, runCatching { backend.groupName(code) }.getOrDefault(""))
    }

    private suspend fun added(context: Context, code: String, alias: String, name: String) {
        DataStoreManager(context).update {
            it.copy(
                alias = alias,
                groups = (it.groups + code).distinct(),
                groupNames = if (name.isEmpty()) it.groupNames else it.groupNames + (code to name)
            )
        }
        schedule(context)
        runCatching { push(context) }
    }

    suspend fun rename(context: Context, alias: String) = withContext(Dispatchers.IO) {
        val backend = Backends.get(context)
        DataStoreManager(context).settings.first().groups.forEach { backend.joinGroup(it, alias) }
        DataStoreManager(context).update { it.copy(alias = alias) }
    }

    /** The last group out takes the shared totals with it. */
    suspend fun leave(context: Context, code: String) = withContext(Dispatchers.IO) {
        val backend = Backends.get(context)
        val store = DataStoreManager(context)
        backend.leaveGroup(code)
        if (store.settings.first().groups.none { it != code }) backend.deleteMyData()
        store.update {
            val left = it.groups - code
            it.copy(groups = left, groupNames = it.groupNames - code, syncFrom = if (left.isEmpty()) "" else it.syncFrom)
        }
        if (store.settings.first().groups.isEmpty()) cancel(context)
    }

    suspend fun deleteAll(context: Context) = withContext(Dispatchers.IO) {
        Backends.get(context).deleteMyData()
        DataStoreManager(context).update { it.copy(groups = emptyList(), groupNames = emptyMap(), syncFrom = "") }
        cancel(context)
    }

    /** Sends only the days that can have changed. Nothing is sent outside a group. */
    suspend fun push(context: Context) = withContext(Dispatchers.IO) {
        SessionManager.reconcile(context)
        val store = DataStoreManager(context)
        val settings = store.settings.first()
        if (settings.groups.isEmpty()) return@withContext
        val today = LocalDate.now().toString()
        val from = settings.syncFrom.ifEmpty { LocalDate.now().minusDays(BACKFILL_DAYS).toString() }
        val rows = AppDatabase.getInstance(context).dayTotalDao().range(from, today)
            .map { dayUpload(it.date, it.focusedMin, it.laptopMin, it.distractedMin) }
        if (rows.isNotEmpty()) Backends.get(context).pushTotals(rows)
        // A laptop code scanned during the upload moved syncFrom back. Keep that.
        store.update { if (it.syncFrom == settings.syncFrom) it.copy(syncFrom = today) else it }
    }

    suspend fun addLaptop(context: Context, day: LaptopDay) = withContext(Dispatchers.IO) {
        val date = day.date.toString()
        AppDatabase.getInstance(context).dayTotalDao().setLaptop(date, day.minutes)
        DataStoreManager(context).update {
            if (it.syncFrom.isNotEmpty() && date < it.syncFrom) it.copy(syncFrom = date) else it
        }
        runCatching { push(context) }
    }

    /** Safe to call often: a job that is already waiting is left alone. */
    fun schedule(context: Context) {
        val jobs = context.getSystemService(JobScheduler::class.java)
        if (jobs.getPendingJob(JOB_ID) != null) return
        jobs.schedule(
            JobInfo.Builder(JOB_ID, ComponentName(context, GroupSyncJob::class.java))
                .setPersisted(true)
                .setPeriodic(TimeUnit.HOURS.toMillis(6))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .build()
        )
    }

    private fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
    }
}

class GroupSyncJob : JobService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onStartJob(params: JobParameters?): Boolean {
        scope.launch {
            val failed = runCatching { GroupSync.push(applicationContext) }.isFailure
            jobFinished(params, failed)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true
}
