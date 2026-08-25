package dev.pixelock.app.hardcoded

/** Screens that can uninstall, stop or disable this app. Watched only while Strict Mode holds. */
object StrictGuardTargets {
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
