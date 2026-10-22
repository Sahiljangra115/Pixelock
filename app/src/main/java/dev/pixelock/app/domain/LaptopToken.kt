package dev.pixelock.app.domain

import java.security.MessageDigest
import java.security.SecureRandom
import java.time.LocalDate
import java.time.format.DateTimeParseException
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class LaptopDay(val date: LocalDate, val minutes: Int)

/**
 * The code tools/pixelock_laptop.py shows: PXL1.date.minutes.signature.
 * The signature uses a key made by the phone at pairing, so only the paired laptop can make one.
 */
object LaptopToken {

    private const val PREFIX = "PXL1"
    private const val KEY_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    private const val KEY_LENGTH = 16
    private const val SIGNATURE_HEX = 32
    private const val MAX_AGE_DAYS = 7L
    private const val MAX_MINUTES = 1440

    fun isToken(text: String): Boolean = text.trim().startsWith("$PREFIX.")

    fun newKey(): String {
        val random = SecureRandom()
        return String(CharArray(KEY_LENGTH) { KEY_ALPHABET[random.nextInt(KEY_ALPHABET.length)] })
    }

    fun signature(body: String, key: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key.toByteArray(), "HmacSHA256"))
        return mac.doFinal(body.toByteArray()).joinToString("") { "%02x".format(it) }.take(SIGNATURE_HEX)
    }

    /** Null when the code is malformed, signed with another key, too old, or from the future. */
    fun parse(text: String, key: String, today: LocalDate): LaptopDay? {
        if (key.isEmpty()) return null
        val parts = text.trim().split('.')
        if (parts.size != 4 || parts[0] != PREFIX) return null
        val expected = signature(parts.take(3).joinToString("."), key)
        if (!MessageDigest.isEqual(expected.toByteArray(), parts[3].toByteArray())) return null

        val date = try {
            LocalDate.parse(parts[1])
        } catch (_: DateTimeParseException) {
            return null
        }
        val minutes = parts[2].toIntOrNull() ?: return null
        if (date.isAfter(today) || date.isBefore(today.minusDays(MAX_AGE_DAYS))) return null
        if (minutes !in 0..MAX_MINUTES) return null
        return LaptopDay(date, minutes)
    }
}
