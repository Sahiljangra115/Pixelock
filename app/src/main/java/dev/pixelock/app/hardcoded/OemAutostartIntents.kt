package dev.pixelock.app.hardcoded

/**
 * Entry points for the "autostart" screens OEM skins hide in their own settings. Android has no
 * standard intent for these, so each one is tried in order for the current maker.
 */
object OemAutostartIntents {

    val candidates: List<Pair<String, String>> = listOf(
        // Xiaomi, Redmi, Poco
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // Realme, Oppo
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oplus.safecenter" to "com.oplus.safecenter.permission.startup.StartupAppListActivity",
        // Vivo, iQOO
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
    )

    /** Values of Build.MANUFACTURER, lower case. See dontkillmyapp.com for what each one does. */
    val swipeKillers = setOf("xiaomi", "redmi", "poco", "oppo", "realme", "oneplus", "vivo", "iqoo", "huawei", "honor")
}
