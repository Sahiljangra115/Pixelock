package dev.pixelock.app.hardcoded

/** Screens that can uninstall, stop or disable this app. */
object StrictGuardTargets {

    /** Windows that switch off device admin. Class names read the same in every language. */
    val removalClasses: List<String> = listOf("DeviceAdminAdd")

    /** With this app's name, these mark a page that can remove it. */
    val removalWords: List<String> = listOf(
        "Force stop", "Uninstall", "Deactivate", "Device admin", "Use Pixelock"
    )

    val packages: Set<String> = setOf(
        "com.android.settings",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller",
        "com.miui.securitycenter",
        "com.samsung.android.lool",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.vivo.permissionmanager",
        "com.iqoo.secure"
    )
}
