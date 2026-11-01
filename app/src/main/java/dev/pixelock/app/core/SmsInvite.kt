package dev.pixelock.app.core

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.telephony.PhoneNumberUtils
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Texts a group invite from this phone's SIM to numbers the user just picked. The carrier sees
 * the numbers. The backend never does. Nothing else in the app may send SMS.
 */
object SmsInvite {

    private const val SENT_ACTION = "dev.pixelock.app.INVITE_SENT"
    // Long enough for the system to ask which SIM to use on a dual SIM phone.
    private const val TIMEOUT_MS = 60_000L

    /** Outlives every screen, so turning the phone or leaving cannot stop a batch halfway. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** One number at a time, so each result is known. Returns the numbers that did not go out. */
    suspend fun send(context: Context, numbers: List<String>, text: String): List<String> {
        val sms = context.applicationContext.getSystemService(SmsManager::class.java)
        return numbers.filterNot { sendOne(context, sms, it, text) }
    }

    private suspend fun sendOne(context: Context, sms: SmsManager, number: String, text: String): Boolean {
        val address = PhoneNumberUtils.normalizeNumber(number)
        if (address.isEmpty()) return false
        val parts = sms.divideMessage(text)
        val sent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(SENT_ACTION).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE
        )
        val result = CompletableDeferred<Boolean>()
        var left = parts.size
        var allOk = true
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                allOk = allOk && resultCode == Activity.RESULT_OK
                if (--left == 0) result.complete(allOk)
            }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(SENT_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            // Throws on a number the phone refuses, a missing SIM, or a permission taken back.
            val accepted = runCatching {
                sms.sendMultipartTextMessage(address, null, parts, ArrayList(parts.map { sent }), null)
            }.isSuccess
            return accepted && withTimeoutOrNull(TIMEOUT_MS) { result.await() } == true
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
