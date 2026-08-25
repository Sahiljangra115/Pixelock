package dev.pixelock.app.hardcoded

/**
 * How to spot a short video viewer inside one app.
 * All [requiredIds] must be on screen, or [accessibilityText] must match a visible node.
 * [minHeightFraction] rejects small previews. [excludeIds] rejects look alike screens.
 * [maxTestedVersionCode], once set, makes newer app versions fall back to blocking the whole app,
 * because a stale selector would otherwise stop blocking without any sign.
 */
data class ReelSelector(
    val packageName: String,
    val requiredIds: List<String> = emptyList(),
    val accessibilityText: String? = null,
    val minHeightFraction: Float = 0f,
    val maxTestedVersionCode: Long? = null
)

object ReelSelectors {

    private fun instagram(pkg: String) = ReelSelector(
        pkg,
        requiredIds = listOf("$pkg:id/clips_viewer_view_pager", "$pkg:id/clips_ufi_component")
    )

    private fun youtube(pkg: String) = ReelSelector(pkg, requiredIds = listOf("$pkg:id/reel_recycler"))

    val all: List<ReelSelector> = listOf(
        instagram("com.instagram.android"),
        youtube("com.google.android.youtube"),
        youtube("app.revanced.android.youtube"),
        ReelSelector("com.facebook.katana", accessibilityText = "Reels tab details"),
        ReelSelector(
            "com.snapchat.android",
            requiredIds = listOf("com.snapchat.android:id/spotlight_container")
        ),
        ReelSelector(
            "com.zhiliaoapp.musically",
            requiredIds = listOf(
                "com.zhiliaoapp.musically:id/view_pager_layout_wrapper",
                "com.zhiliaoapp.musically:id/long_press_layout"
            ),
            minHeightFraction = 0.75f
        )
    )

    val byPackage: Map<String, ReelSelector> = all.associateBy { it.packageName }
}
