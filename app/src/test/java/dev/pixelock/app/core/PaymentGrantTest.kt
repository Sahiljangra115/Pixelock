package dev.pixelock.app.core

import dev.pixelock.app.data.models.Settings
import dev.pixelock.app.data.remote.OrderPurpose
import dev.pixelock.app.domain.ORDER_REUSE_MS
import dev.pixelock.app.domain.PendingOrder
import dev.pixelock.app.domain.canReuse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentGrantTest {

    private val app = PendingOrder("o1", OrderPurpose.UNLOCK_APP, "com.instagram.android")
    private val timer = PendingOrder("o2", OrderPurpose.END_TIMER, "")

    @Test fun `a paid app order opens that one app for the unlock time`() {
        val after = PaymentFlow.afterPayment(Settings(pendingOrders = listOf(app)), app, 1_000L)!!
        assertEquals(1, after.unlocks.size)
        assertEquals("com.instagram.android", after.unlocks[0].packageName)
        assertEquals(1_000L + PaymentFlow.UNLOCK_MINUTES * 60_000L, after.unlocks[0].expiresAtMs)
        assertTrue(after.pendingOrders.isEmpty())
    }

    @Test fun `one order can never grant twice`() {
        val once = PaymentFlow.afterPayment(Settings(pendingOrders = listOf(app)), app, 1_000L)!!
        assertNull(PaymentFlow.afterPayment(once, app, 2_000L))
    }

    @Test fun `an order that is not pending grants nothing`() {
        assertNull(PaymentFlow.afterPayment(Settings(), app, 1_000L))
        assertNull(PaymentFlow.afterPayment(Settings(pendingOrders = listOf(timer)), app, 1_000L))
    }

    @Test fun `a timer order adds no app pass`() {
        val after = PaymentFlow.afterPayment(Settings(pendingOrders = listOf(timer)), timer, 1_000L)!!
        assertTrue(after.unlocks.isEmpty())
        assertTrue(after.pendingOrders.isEmpty())
    }

    @Test fun `paying one order leaves the others waiting`() {
        val both = Settings(pendingOrders = listOf(app, timer))
        val after = PaymentFlow.afterPayment(both, app, 1_000L)!!
        assertEquals(listOf(timer), after.pendingOrders)
        assertTrue(PaymentFlow.afterPayment(after, timer, 2_000L)!!.pendingOrders.isEmpty())
    }

    @Test fun `a fresh order for the same thing is reused so nobody pays twice`() {
        val open = app.copy(url = "https://rzp.io/i/x", createdAtMs = 1_000L)
        assertTrue(open.canReuse(OrderPurpose.UNLOCK_APP, app.packageName, 2_000L))
        assertFalse(open.canReuse(OrderPurpose.UNLOCK_APP, "com.other", 2_000L))
        assertFalse(open.canReuse(OrderPurpose.END_TIMER, app.packageName, 2_000L))
        assertFalse(open.canReuse(OrderPurpose.UNLOCK_APP, app.packageName, 1_000L + ORDER_REUSE_MS))
        assertFalse(app.canReuse(OrderPurpose.UNLOCK_APP, app.packageName, 2_000L))
    }
}
