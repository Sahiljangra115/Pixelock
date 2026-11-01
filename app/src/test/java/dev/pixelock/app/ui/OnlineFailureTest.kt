package dev.pixelock.app.ui

import dev.pixelock.app.R
import dev.pixelock.app.data.remote.BackendErrors
import dev.pixelock.app.data.remote.BackendException
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

class OnlineFailureTest {

    @Test
    fun aBackendFailureKeepsItsOwnMessage() {
        assertEquals(R.string.err_offline, BackendException(BackendErrors.OFFLINE).onlineMessageRes())
        assertEquals(R.string.err_group_full, BackendException("group_full").onlineMessageRes())
    }

    @Test
    fun anythingElseIsAPlainErrorAndNotACrash() {
        assertEquals(R.string.err_server, IOException("disk full").onlineMessageRes())
        assertEquals(R.string.err_server, IllegalStateException("after onSaveInstanceState").onlineMessageRes())
    }

    @Test
    fun cancellationGoesBackUp() {
        val cancelled = CancellationException("left the screen")
        assertEquals(cancelled, assertThrows(CancellationException::class.java) { cancelled.onlineMessageRes() })
    }
}
