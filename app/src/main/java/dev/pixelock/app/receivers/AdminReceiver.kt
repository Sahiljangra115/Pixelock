package dev.pixelock.app.receivers

import android.app.admin.DeviceAdminReceiver

/** Holds no policies. Being an active admin is what makes the system refuse an uninstall. */
class AdminReceiver : DeviceAdminReceiver()
