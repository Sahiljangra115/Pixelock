package dev.pixelock.app.hardcoded

/**
 * Apps no rule may ever block. The phone's own dialer, SMS app, launchers and keyboards are added
 * at runtime by DeviceAllowList, so this table covers only what can be named ahead of time.
 */
object AlwaysAllow {
    val packages: Set<String> = setOf(
        // System
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.credentialmanager",
        // Calls
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.android.incallui",
        "com.google.android.dialer",
        "com.samsung.android.dialer",
        "com.samsung.android.incallui",
        "com.android.contacts",
        "com.google.android.contacts",
        "com.samsung.android.app.contacts",
        // SMS
        "com.android.mms",
        "com.android.messaging",
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        // Email
        "com.google.android.gm",
        "com.microsoft.office.outlook",
        "com.samsung.android.email.provider",
        // Maps and travel
        "com.google.android.apps.maps",
        "com.ubercab",
        "com.olacabs.customer",
        "com.rapido.passenger",
        "in.redbus.android",
        "com.makemytrip",
        "cris.org.in.prs.ima",
        // UPI, so a paid unlock can finish while a timer runs
        "com.google.android.apps.nbu.paisa.user",
        "com.phonepe.app",
        "net.one97.paytm",
        "in.org.npci.upiapp",
        // Emergency
        "com.android.emergency",
        "com.google.android.apps.safetyhub",
        "com.sec.android.app.safetyassurance",
        "com.samsung.android.emergency"
    )
}
