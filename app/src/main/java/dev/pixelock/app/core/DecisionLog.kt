package dev.pixelock.app.core

import android.content.Context
import dev.pixelock.app.BuildConfig
import java.io.File
import java.time.LocalTime

/** Debug only trace of blocks, so a wrong block can be read in the developer menu without adb. */
object DecisionLog {
    private const val MAX_BYTES = 32 * 1024L

    private fun file(context: Context) = File(context.filesDir, "decisions.txt")

    fun add(context: Context, line: String) {
        if (!BuildConfig.DEBUG) return
        runCatching {
            val f = file(context)
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES.toInt() / 2))
            f.appendText("${LocalTime.now().withNano(0)} $line\n")
        }
    }

    fun read(context: Context): String =
        runCatching { file(context).readText().lines().takeLast(100).reversed().joinToString("\n") }
            .getOrDefault("")
}
