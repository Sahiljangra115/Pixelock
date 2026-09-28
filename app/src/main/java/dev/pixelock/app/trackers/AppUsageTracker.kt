package dev.pixelock.app.trackers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import dev.pixelock.app.data.db.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks foreground time for distraction apps only and rolls it into DayTotal as it happens.
 * The tick is the only timer the blocker has, and it runs only while one of those apps is open.
 * Rechecking on the tick catches routine starts, used up bags and expired unlocks.
 */
class AppUsageTracker(
    private val context: Context,
    private val isTracked: (String) -> Boolean,
    private val onTick: () -> Unit
) {

    private companion object {
        const val TICK_MS = 30_000L
        const val KEEP_DAYS = 90L
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val main = Handler(Looper.getMainLooper())
    private val db by lazy { AppDatabase.getInstance(context) }

    private val usedMs = ConcurrentHashMap<String, Long>()
    private var usedDate = ""

    private var foreground = ""
    private var current: String? = null
    private var lastCommit = 0L
    private var screenOn = true

    fun setup() {
        screenOn = context.getSystemService(PowerManager::class.java).isInteractive
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        context.registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        scope.launch {
            db.appUsageDao().deleteBefore(LocalDate.now().minusDays(KEEP_DAYS).toString())
            loadToday()
        }
    }

    fun onForeground(pkg: String) {
        foreground = pkg
        if (pkg == current) return
        stop()
        if (screenOn && isTracked(pkg)) start(pkg)
    }

    fun usedMinutes(pkg: String): Int = ((usedMs[pkg] ?: 0L) / 60_000L).toInt()

    private fun start(pkg: String) {
        current = pkg
        lastCommit = SystemClock.elapsedRealtime()
        main.postDelayed(tick, TICK_MS)
    }

    private fun stop() {
        commit()
        current = null
        main.removeCallbacks(tick)
    }

    private fun commit() {
        val pkg = current ?: return
        val now = SystemClock.elapsedRealtime()
        val ms = now - lastCommit
        lastCommit = now
        if (ms <= 0) return
        scope.launch {
            val date = LocalDate.now().toString()
            if (date != usedDate) loadToday()
            usedMs.merge(pkg, ms, Long::plus)
            db.appUsageDao().add(date, pkg, ms)
            db.dayTotalDao().addDistracted(date, ms)
        }
    }

    private suspend fun loadToday() {
        val date = LocalDate.now().toString()
        val rows = db.appUsageDao().forDate(date)
        usedMs.clear()
        rows.forEach { usedMs[it.packageName] = it.totalTime }
        usedDate = date
    }

    private val tick = object : Runnable {
        override fun run() {
            commit()
            onTick()
            if (current != null) main.postDelayed(this, TICK_MS)
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    screenOn = false
                    stop()
                }
                Intent.ACTION_USER_PRESENT -> {
                    screenOn = true
                    onForeground(foreground)
                    onTick()
                }
            }
        }
    }

    fun onDestroy() {
        stop()
        main.removeCallbacksAndMessages(null)
        context.unregisterReceiver(screenReceiver)
        // Let the last commit land, then stop accepting work.
        scope.launch { scope.cancel() }
    }
}
