package dev.pixelock.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class FriendsTest {

    @Test fun `group code comes out of typed text, app links and web links`() {
        assertEquals("23456789ABCDEFGH", parseGroupCode(" 23456789abcdefgh "))
        assertEquals("23456789ABCDEFGH", parseGroupCode("pixelock://join/23456789ABCDEFGH"))
        assertEquals("23456789ABCDEFGH", parseGroupCode("https://x.supabase.co/functions/v1/join?c=23456789ABCDEFGH"))
    }

    @Test fun `anything that is not a code is refused`() {
        listOf("", "short", "23456789ABCDEFGH2", "O0OOOOOOOO", "23456789ABCDEFGH; drop", "evil.com/23456789ABCDEFGH/x")
            .forEach { assertNull(it, parseGroupCode(it)) }
    }

    @Test fun `setup script hash matches the app, so the admin passcode opens the hidden screen`() {
        val fromPython = "LQx3A5lom6a6y+ehEBT1ovXVkl/KvukyTItJ5jnyxGw="
        assertTrue(EndPassword.matches("correct horse", "AAECAwQFBgcICQoLDA0ODw==", fromPython))
        assertFalse(EndPassword.matches("wrong horse", "AAECAwQFBgcICQoLDA0ODw==", fromPython))
    }

    @Test fun `invite link uses the server when there is one`() {
        assertEquals("pixelock://join/23456789ABCDEFGH", inviteLink("", "23456789ABCDEFGH"))
        assertEquals("https://a.co/functions/v1/join?c=23456789ABCDEFGH", inviteLink("https://a.co", "23456789ABCDEFGH"))
    }

    @Test fun `board ranges end today and weeks start on monday`() {
        val friday = LocalDate.of(2026, 10, 30)
        assertEquals(friday to friday, BoardPeriod.TODAY.range(friday))
        assertEquals(LocalDate.of(2026, 10, 26) to friday, BoardPeriod.WEEK.range(friday))
        assertEquals(LocalDate.of(2026, 10, 1) to friday, BoardPeriod.MONTH.range(friday))
        val monday = LocalDate.of(2026, 10, 26)
        assertEquals(monday to monday, BoardPeriod.WEEK.range(monday))
    }

    @Test fun `upload adds laptop time and stays inside the server limits`() {
        assertEquals(DayUpload("d", 150, 20), dayUpload("d", 100, 50, 20))
        assertEquals(DayUpload("d", 2880, 1440), dayUpload("d", 2000, 2000, 5000))
        assertEquals(DayUpload("d", 0, 0), dayUpload("d", -5, 0, -1))
    }

    @Test fun `payment page stays on razorpay and may only hand over to upi`() {
        assertEquals(PayRoute.STAY, payRoute("https", "rzp.io"))
        assertEquals(PayRoute.STAY, payRoute("https", "api.razorpay.com"))
        assertEquals(PayRoute.OPEN_APP, payRoute("upi", "pay"))
        assertEquals(PayRoute.OPEN_APP, payRoute("intent", "pay"))
        assertEquals(PayRoute.REFUSE, payRoute("http", "rzp.io"))
        assertEquals(PayRoute.REFUSE, payRoute("https", "evilrazorpay.com"))
        assertEquals(PayRoute.REFUSE, payRoute("https", "razorpay.com.evil.io"))
        assertEquals(PayRoute.REFUSE, payRoute("file", ""))
        assertEquals(PayRoute.REFUSE, payRoute("javascript", null))
        assertEquals(PayRoute.REFUSE, payRoute(null, null))
    }
}
