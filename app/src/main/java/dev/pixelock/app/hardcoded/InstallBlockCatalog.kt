package dev.pixelock.app.hardcoded

import dev.pixelock.app.domain.BannedApp

/** Apps offered on the hidden install block page, and the pages that can install them. */
object InstallBlockCatalog {

    val apps: List<BannedApp> = listOf(
        BannedApp("Instagram", listOf("com.instagram.android")),
        BannedApp("Instagram Lite", listOf("com.instagram.lite")),
        BannedApp("TikTok", listOf("com.zhiliaoapp.musically", "com.ss.android.ugc.trill")),
        BannedApp("TikTok Lite", listOf("com.zhiliaoapp.musically.go", "com.ss.android.ugc.trill.go", "com.tiktok.lite.go")),
        BannedApp("Facebook", listOf("com.facebook.katana")),
        BannedApp("Facebook Lite", listOf("com.facebook.lite")),
        BannedApp("Snapchat", listOf("com.snapchat.android")),
        BannedApp("Threads", listOf("com.instagram.barcelona")),
        BannedApp("X", listOf("com.twitter.android")),
        BannedApp("Reddit", listOf("com.reddit.frontpage")),
        BannedApp("Moj", listOf("in.mohalla.video")),
        BannedApp("Josh", listOf("com.eterno.shortvideos")),
        BannedApp("ShareChat", listOf("in.mohalla.sharechat"))
    )

    /** On from the first run. The rest are the user's choice. */
    val defaults: List<BannedApp> =
        apps.filter { it.name in setOf("Instagram", "Instagram Lite", "TikTok", "TikTok Lite") }

    /** The system's install prompt. Its Install button is hidden from this service. */
    val installers: Set<String> = setOf(
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.samsung.android.packageinstaller",
        "com.miui.packageinstaller"
    )

    /** Watched only while one of these is in front. */
    val installPages: Set<String> = installers + setOf(
        "com.android.vending",
        "com.sec.android.app.samsungapps",
        "com.xiaomi.mipicks",
        "com.heytap.market",
        "com.oppo.market",
        "com.bbk.appstore"
    )
}
