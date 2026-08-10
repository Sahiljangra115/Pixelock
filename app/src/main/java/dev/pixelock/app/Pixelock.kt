package dev.pixelock.app

import android.app.Application

class Pixelock : Application() {
    override fun onCreate() {
        super.onCreate()
        val process = getProcessName()
        Thread.setDefaultUncaughtExceptionHandler(
            CrashLogger(this, isBlockerProcess = process.endsWith(":blocker"), isMainProcess = process == packageName)
        )
    }
}
