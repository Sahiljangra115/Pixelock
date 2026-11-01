package dev.pixelock.app.domain

import dev.pixelock.app.hardcoded.StrictGuardTargets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallBlockTest {

    private val insta = BannedApp("Instagram", listOf("com.instagram.android"))
    private val tiktok = BannedApp("TikTok", listOf("com.zhiliaoapp.musically"))
    private val words = StrictGuardTargets.removalWords

    @Test fun `titles match alone or with a tagline, not as part of a longer name`() {
        assertTrue(isAppTitle("Instagram", "Instagram"))
        assertTrue(isAppTitle(" instagram ", "Instagram"))
        assertTrue(isAppTitle("TikTok - Videos, Shop & LIVE", "TikTok"))
        assertTrue(isAppTitle("TikTok: Videos", "TikTok"))
        assertTrue(isAppTitle("TikTok \u2014 Videos", "TikTok"))
        assertFalse(isAppTitle("Instagram Lite", "Instagram"))
        assertFalse(isAppTitle("Facebook Messenger", "Facebook"))
        assertFalse(isAppTitle("Tips for Instagram", "Instagram"))
        assertFalse(isAppTitle("Instagram", ""))
    }

    @Test fun `a page needs the name and an install button`() {
        assertEquals(insta, bannedOnInstallPage(listOf("Instagram", "Meta", "Install"), listOf(tiktok, insta)))
        assertEquals(tiktok, bannedOnInstallPage(listOf("INSTALL", "TikTok - Videos"), listOf(tiktok)))
        assertNull("no install button, so the app is on the phone already", bannedOnInstallPage(listOf("Instagram", "Open", "Uninstall"), listOf(insta)))
        assertNull("nothing banned", bannedOnInstallPage(listOf("Spotify", "Install"), listOf(insta)))
        assertNull("nothing banned at all", bannedOnInstallPage(listOf("Instagram", "Install"), emptyList()))
    }

    @Test fun `an installer page needs only the name, because its button is hidden`() {
        val prompt = listOf("Instagram", "Do you want to install this app?", "Cancel")
        assertEquals(insta, bannedOnInstallPage(prompt, listOf(insta), installer = true))
        assertNull(bannedOnInstallPage(prompt, listOf(insta)))
        assertNull(bannedOnInstallPage(listOf("Spotify", "Cancel"), listOf(insta), installer = true))
    }

    @Test fun `removal pages close, plain lists do not`() {
        assertTrue(isRemovalPage(listOf("App info", "Pixelock", "Force stop"), "Pixelock", words))
        assertTrue(isRemovalPage(listOf("Pixelock Debug", "Do you want to uninstall this app?"), "Pixelock", words))
        assertTrue(isRemovalPage(listOf("Device admin apps", "Pixelock"), "Pixelock", words))
        assertTrue(isRemovalPage(listOf("Pixelock", "Deactivate this device admin app"), "Pixelock", words))
        assertTrue(isRemovalPage(listOf("Pixelock Debug", "Use Pixelock Debug"), "Pixelock", words))
        assertFalse("setup needs this list", isRemovalPage(listOf("Accessibility", "Pixelock", "On"), "Pixelock", words))
        assertFalse("setup needs this page", isRemovalPage(listOf("Pixelock", "Allow notifications"), "Pixelock", words))
        assertFalse("other apps stay open", isRemovalPage(listOf("Spotify", "Force stop"), "Pixelock", words))
    }
}
