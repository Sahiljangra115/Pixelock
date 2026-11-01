package dev.pixelock.app.data.remote

import android.content.Context
import dev.pixelock.app.BuildConfig
import dev.pixelock.app.domain.BoardRow
import dev.pixelock.app.domain.DayUpload

/** [code] is a BackendErrors value, or a failure raised by the database. */
class BackendException(val code: String) : Exception(code)

object BackendErrors {
    const val OFFLINE = "offline"
    const val AUTH = "auth"
    const val NOT_CONFIGURED = "not_configured"
    const val SERVER = "server"
}

object OrderPurpose {
    const val UNLOCK_APP = "unlock_app"
    const val END_TIMER = "end_timer"
}

object OrderStatus {
    const val PAID = "paid"
    const val CONSUMED = "consumed"
}

data class Order(val id: String, val url: String)

/** Blocking calls. Run them off the main thread. Throws [BackendException] on any failure. */
interface Backend {
    fun createGroup(alias: String): String
    fun joinGroup(code: String, alias: String)
    fun leaveGroup(code: String)
    fun board(code: String, from: String, to: String): List<BoardRow>
    fun pushTotals(rows: List<DayUpload>)
    fun deleteMyData()
    /** A one time code that links a laptop to this phone's id. */
    fun createPairCode(): String
    fun createOrder(purpose: String): Order
    fun orderStatus(id: String): String
    fun consumeOrder(id: String): Boolean
}

object Backends {

    @Volatile private var real: Backend? = null

    /** The fake can never ship: release builds compile this branch out. */
    fun get(context: Context): Backend {
        if (BuildConfig.DEBUG && BuildConfig.SUPABASE_URL.isBlank()) return FakeBackend
        return real ?: synchronized(this) {
            real ?: SupabaseBackend(
                BuildConfig.SUPABASE_URL,
                BuildConfig.SUPABASE_ANON_KEY,
                PrefsTokenStore(context.applicationContext)
            ).also { real = it }
        }
    }
}
