package dev.pixelock.app

import dev.pixelock.app.hardcoded.AlwaysAllow
import dev.pixelock.app.hardcoded.DefaultBlockList
import dev.pixelock.app.hardcoded.OemAutostartIntents
import dev.pixelock.app.hardcoded.ReelSelectors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DataTablesTest {

    private val pkg = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)*$")
    private val viewId = Regex("^[a-zA-Z][a-zA-Z0-9_.]*:id/[a-zA-Z0-9_]+$")

    @Test fun `always allow entries are package names`() {
        AlwaysAllow.packages.forEach { assertTrue(it, pkg.matches(it)) }
    }

    @Test fun `critical apps are in the always allow table`() {
        listOf(
            "com.android.systemui", "com.android.phone", "com.android.settings",
            "com.google.android.dialer", "com.google.android.apps.messaging",
            "com.google.android.apps.maps", "com.android.emergency"
        ).forEach { assertTrue(it, it in AlwaysAllow.packages) }
    }

    @Test fun `default block list is clean`() {
        DefaultBlockList.packages.forEach { assertTrue(it, pkg.matches(it)) }
        assertEquals(DefaultBlockList.packages.size, DefaultBlockList.packages.toSet().size)
        assertTrue(DefaultBlockList.packages.none { it in AlwaysAllow.packages })
    }

    @Test fun `reel selectors are well formed`() {
        assertEquals(ReelSelectors.all.size, ReelSelectors.byPackage.size)
        ReelSelectors.all.forEach { s ->
            assertTrue(s.packageName, pkg.matches(s.packageName))
            assertTrue(s.packageName, s.requiredIds.isNotEmpty() || !s.accessibilityText.isNullOrBlank())
            s.requiredIds.forEach { id ->
                assertTrue(id, viewId.matches(id))
                assertTrue(id, id.startsWith(s.packageName + ":id/"))
            }
            assertTrue(s.minHeightFraction in 0f..1f)
        }
    }

    @Test fun `oem autostart entries name a package and a class inside it`() {
        OemAutostartIntents.candidates.forEach { (p, cls) ->
            assertTrue(p, pkg.matches(p))
            assertTrue(cls, pkg.matches(cls) && cls.contains('.'))
        }
    }
}
