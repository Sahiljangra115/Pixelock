package dev.pixelock.app

import android.content.Context
import android.content.Intent
import android.os.Build
import dev.pixelock.app.core.CrashGuard
import dev.pixelock.app.ui.activity.CrashLogActivity
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.LocalDateTime

class CrashLogger(
    private val context: Context,
    private val isBlockerProcess: Boolean = false,
    private val isMainProcess: Boolean = false
) : Thread.UncaughtExceptionHandler {

    private val logFile = File(context.filesDir, "crash_log.txt")

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            write("Crash", throwable)
            // Only the main process opens a screen. From the blocker, a crash loop would throw it
            // over every app. From the crash screen itself, it would reopen forever.
            if (isBlockerProcess) CrashGuard.recordServiceCrash(context, System.currentTimeMillis())
            if (isMainProcess) {
                context.startActivity(
                    Intent(context, CrashLogActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                )
            }
        } catch (_: Exception) {
        }
        android.os.Process.killProcess(android.os.Process.myPid())
        System.exit(10)
    }

    fun logNonFatalError(exception: Exception) {
        runCatching { write("Caught error", exception) }
    }

    private fun write(kind: String, error: Throwable) {
        if (logFile.length() > MAX_BYTES) logFile.delete()
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }
        logFile.appendText(
            "\n--- $kind at ${LocalDateTime.now()} on ${Build.MANUFACTURER} ${Build.MODEL} ---\n$trace"
        )
    }

    private companion object {
        const val MAX_BYTES = 256 * 1024L
    }
}
