package dev.pixelock.app.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** The only numbers that ever leave the phone. */
data class DayUpload(val date: String, val focusedMin: Int, val distractedMin: Int)

data class BoardRow(
    val alias: String,
    val focusedMin: Long,
    val distractedMin: Long,
    val isMe: Boolean,
    /** Focused minutes by device. focusedMin is the total of both. */
    val phoneMin: Long = 0,
    val laptopMin: Long = 0
)

enum class BoardPeriod { TODAY, WEEK, MONTH }

const val ALIAS_MAX = 24

// Same limits as the checks on the day_totals table.
private const val MAX_FOCUSED_MIN = 2880
private const val MAX_DISTRACTED_MIN = 1440

/** Weeks start on Monday. Ranges end today, so a new week reads true. */
fun BoardPeriod.range(today: LocalDate): Pair<LocalDate, LocalDate> = when (this) {
    BoardPeriod.TODAY -> today to today
    BoardPeriod.WEEK -> today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)) to today
    BoardPeriod.MONTH -> today.withDayOfMonth(1) to today
}

fun dayUpload(date: String, phoneMin: Int, laptopMin: Int, distractedMin: Int) = DayUpload(
    date,
    (phoneMin + laptopMin).coerceIn(0, MAX_FOCUSED_MIN),
    distractedMin.coerceIn(0, MAX_DISTRACTED_MIN)
)

private val CODE_AT_END = Regex("(?:^|[/=])([2-9A-Ha-h]{16})$")

/** Finds a group code in a typed code, a scanned QR, or an invite link. Null when there is none. */
fun parseGroupCode(text: String): String? =
    CODE_AT_END.find(text.trim())?.groupValues?.get(1)?.uppercase()

/** A link friends can tap. With no server set up, only the app's own scheme is left. */
fun inviteLink(baseUrl: String, code: String): String =
    if (baseUrl.isBlank()) "pixelock://join/$code" else "$baseUrl/functions/v1/join?c=$code"

private val EMAIL = Regex("[^\\s@,;<>]+@[^\\s@,;<>]+\\.[^\\s@,;<>]+")

/** Every email address in what was typed, however they are split up. */
fun parseEmails(text: String): List<String> = EMAIL.findAll(text).map { it.value }.distinct().toList()

fun isEmail(address: String): Boolean = EMAIL.matches(address)

enum class PayRoute { STAY, OPEN_APP, REFUSE }

private val PAY_HOSTS = setOf("rzp.io", "razorpay.com")
private val UPI_SCHEMES = setOf("upi", "tez", "gpay", "phonepe", "paytmmp", "bhim", "credpay")

fun isUpiScheme(scheme: String?): Boolean = scheme?.lowercase() in UPI_SCHEMES

/** The payment page may stay on Razorpay or hand over to a UPI app. Nothing else gets through. */
fun payRoute(scheme: String?, host: String?): PayRoute {
    val name = host?.lowercase().orEmpty()
    return when {
        scheme?.lowercase() == "https" &&
            (name in PAY_HOSTS || PAY_HOSTS.any { name.endsWith(".$it") }) -> PayRoute.STAY
        scheme?.lowercase() == "intent" || isUpiScheme(scheme) -> PayRoute.OPEN_APP
        else -> PayRoute.REFUSE
    }
}
