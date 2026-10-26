package dev.pixelock.app.core

import android.content.Context
import dev.pixelock.app.data.DataStoreManager
import dev.pixelock.app.data.models.Settings
import dev.pixelock.app.data.remote.BackendException
import dev.pixelock.app.data.remote.Backends
import dev.pixelock.app.data.remote.OrderPurpose
import dev.pixelock.app.data.remote.OrderStatus
import dev.pixelock.app.domain.PendingOrder
import dev.pixelock.app.domain.Unlock
import dev.pixelock.app.domain.canReuse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Turns a payment into an unlock. Only the backend can mark an order paid, so nothing here can be
 * tricked by a tap. The pending order is kept until the grant, so a killed app loses no payment.
 */
object PaymentFlow {

    const val UNLOCK_MINUTES = 15

    /** Links die after 20 minutes, so an order this old can never be paid. */
    private const val ORDER_FORGET_MS = 60 * 60_000L

    /** The order to pay. Null means an earlier payment for the same thing just came through. */
    suspend fun begin(context: Context, purpose: String, packageName: String): PendingOrder? = withContext(Dispatchers.IO) {
        val store = DataStoreManager(context)
        val settled = settlePending(context)
        if (settled.any { it.purpose == purpose && it.packageName == packageName }) return@withContext null
        val now = System.currentTimeMillis()
        store.settings.first().pendingOrders.firstOrNull { it.canReuse(purpose, packageName, now) }
            ?.let { return@withContext it }

        val order = Backends.get(context).createOrder(purpose)
        val pending = PendingOrder(order.id, purpose, packageName, order.url, now)
        // Older orders stay: a link left open in a UPI app can still be paid.
        store.update { s ->
            s.copy(pendingOrders = s.pendingOrders.filter { now - it.createdAtMs < ORDER_FORGET_MS } + pending)
        }
        pending
    }

    /** True once this order has been granted, by this call or an earlier one. */
    suspend fun settle(context: Context, order: PendingOrder): Boolean = withContext(Dispatchers.IO) {
        val pending = DataStoreManager(context).settings.first().pendingOrders
        if (pending.none { it.id == order.id }) return@withContext true
        val backend = Backends.get(context)
        when (backend.orderStatus(order.id)) {
            OrderStatus.PAID -> backend.consumeOrder(order.id) && grant(context, order)
            // Consumed, then killed before the grant. The pending entry proves it was never granted.
            OrderStatus.CONSUMED -> grant(context, order)
            else -> false
        }
    }

    /** Returns the orders it granted. Never throws, so it is safe at app start. */
    suspend fun settlePending(context: Context): List<PendingOrder> =
        DataStoreManager(context).settings.first().pendingOrders.filter { order ->
            try {
                settle(context, order)
            } catch (_: BackendException) {
                false
            }
        }

    private suspend fun grant(context: Context, order: PendingOrder): Boolean {
        val endTimer = order.purpose == OrderPurpose.END_TIMER
        val strict = StrictModeController(context)
        if (endTimer) SessionManager.reconcile(context)
        var granted = false
        DataStoreManager(context).update { s ->
            // One atomic write: only the caller that clears the order gets to grant it, and a
            // crash cannot land between taking the payment and ending the timer.
            val next = afterPayment(s, order, System.currentTimeMillis()) ?: return@update s
            granted = true
            if (endTimer) strict.disarmed(next).copy(session = null) else next
        }
        if (granted && endTimer) SessionManager.cancelEnd(context)
        return granted
    }

    /** Null when this order is not the pending one, which is what stops a second grant. */
    internal fun afterPayment(settings: Settings, order: PendingOrder, nowMs: Long): Settings? {
        if (settings.pendingOrders.none { it.id == order.id }) return null
        val left = settings.pendingOrders.filter { it.id != order.id }
        if (order.purpose != OrderPurpose.UNLOCK_APP) return settings.copy(pendingOrders = left)
        val unlock = Unlock(order.packageName, nowMs + UNLOCK_MINUTES * 60_000L)
        return settings.copy(pendingOrders = left, unlocks = settings.unlocks + unlock)
    }
}
