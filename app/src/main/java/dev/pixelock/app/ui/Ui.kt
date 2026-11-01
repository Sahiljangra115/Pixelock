package dev.pixelock.app.ui

import android.content.Context
import android.os.CountDownTimer
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.timepicker.MaterialTimePicker
import com.google.android.material.timepicker.TimeFormat
import dev.pixelock.app.R
import dev.pixelock.app.core.SessionManager
import dev.pixelock.app.data.DataStoreManager
import dev.pixelock.app.data.models.Settings
import dev.pixelock.app.data.remote.BackendErrors
import dev.pixelock.app.data.remote.BackendException
import dev.pixelock.app.domain.EndPassword
import dev.pixelock.app.domain.MINUTES_PER_DAY
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Runs [block] with fresh settings while the view is started. */
fun Fragment.onSettings(block: (Settings) -> Unit) {
    val owner = viewLifecycleOwner
    owner.lifecycleScope.launch {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            DataStoreManager(requireContext()).settings.collect(block)
        }
    }
}

/** Writes a settings change without blocking the UI. Finishes even if the screen closes first. */
fun Fragment.updateSettings(transform: (Settings) -> Settings) {
    val context = requireContext().applicationContext
    viewLifecycleOwner.lifecycleScope.launch {
        withContext(NonCancellable) { DataStoreManager(context).update(transform) }
    }
}

fun minuteLabel(minutes: Int): String = "%02d:%02d".format(minutes / 60, minutes % 60)

/** "1h 20m" or "20m". */
fun Context.durationLabel(minutes: Int): String =
    when {
        minutes < 60 -> getString(R.string.duration_m, minutes)
        minutes % 60 == 0 -> getString(R.string.duration_h, minutes / 60)
        else -> getString(R.string.duration_hm, minutes / 60, minutes % 60)
    }

/** Dims a control the user cannot use right now, and stops it taking touches. */
fun View.setUsable(usable: Boolean) {
    isEnabled = usable
    alpha = if (usable) 1f else 0.45f
}

/** Runs a server call from a screen. A failure becomes a short message, never a crash. */
fun Fragment.online(action: suspend () -> Unit) {
    val context = requireContext().applicationContext
    viewLifecycleOwner.lifecycleScope.launch {
        try {
            action()
        } catch (e: BackendException) {
            Toast.makeText(context, e.messageRes(), Toast.LENGTH_LONG).show()
        }
    }
}

fun BackendException.messageRes(): Int = when (code) {
    "group_not_found" -> R.string.err_group_not_found
    "group_full" -> R.string.err_group_full
    "too_many_groups" -> R.string.err_too_many_groups
    "not_a_member" -> R.string.err_not_a_member
    BackendErrors.OFFLINE -> R.string.err_offline
    BackendErrors.AUTH -> R.string.err_auth
    BackendErrors.NOT_CONFIGURED -> R.string.err_not_configured
    else -> R.string.err_server
}

/** One line text dialog. [onDone] gets the trimmed text and is skipped when it is empty. */
fun Fragment.askText(title: Int, hint: Int, initial: String, maxLength: Int, onDone: (String) -> Unit) {
    val context = requireContext()
    val input = EditText(context).apply {
        setHint(hint)
        setText(initial)
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT
        filters = arrayOf(InputFilter.LengthFilter(maxLength))
    }
    val gap = (24 * resources.displayMetrics.density).toInt()
    val frame = FrameLayout(context).apply { setPadding(gap, gap / 2, gap, 0); addView(input) }
    MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setView(frame)
        .setPositiveButton(R.string.confirm) { _, _ ->
            input.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onDone)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/** Whole minutes in [range]. A value outside it is refused, not clamped, so a typo saves nothing. */
fun Fragment.askMinutes(title: Int, initial: Int, range: IntRange, onDone: (Int) -> Unit) {
    val context = requireContext()
    val input = EditText(context).apply {
        setHint(R.string.minutes)
        setText(initial.toString())
        setSingleLine()
        inputType = InputType.TYPE_CLASS_NUMBER
        filters = arrayOf(InputFilter.LengthFilter(4))
        selectAll()
    }
    val gap = (24 * resources.displayMetrics.density).toInt()
    val frame = FrameLayout(context).apply { setPadding(gap, gap / 2, gap, 0); addView(input) }
    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setMessage(getString(R.string.minutes_range, range.first, range.last))
        .setView(frame)
        .setPositiveButton(R.string.confirm) { _, _ ->
            val minutes = input.text.toString().toIntOrNull()
            if (minutes != null && minutes in range) onDone(minutes)
            else Toast.makeText(context, getString(R.string.minutes_range, range.first, range.last), Toast.LENGTH_LONG).show()
        }
        .setNegativeButton(R.string.cancel, null)
        .create()
    dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE)
    dialog.show()
    input.requestFocus()
}

/** A clock time, typed with the keyboard. [onDone] gets minutes since midnight. */
fun Fragment.askTime(title: Int, minutes: Int, onDone: (Int) -> Unit) {
    val time = minutes.coerceIn(0, MINUTES_PER_DAY - 1)
    val picker = MaterialTimePicker.Builder()
        .setTimeFormat(TimeFormat.CLOCK_24H)
        .setInputMode(MaterialTimePicker.INPUT_MODE_KEYBOARD)
        .setHour(time / 60)
        .setMinute(time % 60)
        .setTitleText(title)
        .build()
    picker.addOnPositiveButtonClickListener { onDone(picker.hour * 60 + picker.minute) }
    picker.show(childFragmentManager, "time")
}

/** Like [askText], but hidden, and the text is kept exactly as typed. */
fun Fragment.askSecret(title: Int, message: String?, onDone: (String) -> Unit) {
    val context = requireContext()
    val input = EditText(context).apply {
        setSingleLine()
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
    }
    val gap = (24 * resources.displayMetrics.density).toInt()
    val frame = FrameLayout(context).apply { setPadding(gap, gap / 2, gap, 0); addView(input) }
    MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setMessage(message)
        .setView(frame)
        .setPositiveButton(R.string.confirm) { _, _ ->
            input.text.toString().takeIf { it.isNotEmpty() }?.let(onDone)
        }
        .setNegativeButton(R.string.cancel, null)
        .show()
}

/** Asks twice, then saves. [onSaved] does not run when the save is refused. */
fun Fragment.askNewPassword(onSaved: () -> Unit) {
    val context = requireContext().applicationContext
    val toast = { message: Int -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    askSecret(R.string.new_password_t, getString(R.string.new_password_m, EndPassword.MIN_LENGTH)) { first ->
        if (first.length < EndPassword.MIN_LENGTH) {
            toast(R.string.new_password_short)
            return@askSecret
        }
        askSecret(R.string.new_password_again_t, null) { second ->
            if (second != first) {
                toast(R.string.new_password_differs)
                return@askSecret
            }
            viewLifecycleOwner.lifecycleScope.launch {
                if (SessionManager.setPassword(context, first)) {
                    toast(R.string.new_password_saved)
                    onSaved()
                } else {
                    toast(R.string.edit_refused)
                }
            }
        }
    }
}

/** The confirm button stays off for [seconds], so a weak moment has time to pass. */
fun Fragment.confirmAfterWait(title: Int, message: String, seconds: Int, onConfirm: () -> Unit) {
    val context = requireContext()
    val dialog = MaterialAlertDialogBuilder(context)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton(R.string.confirm) { _, _ -> onConfirm() }
        .setNegativeButton(R.string.cancel, null)
        .show()
    val button = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
    button.isEnabled = false
    val wait = object : CountDownTimer(seconds * 1000L, 250L) {
        override fun onTick(left: Long) {
            button.text = context.getString(R.string.confirm_wait, (left + 999) / 1000)
        }

        override fun onFinish() {
            button.setText(R.string.confirm)
            button.isEnabled = true
        }
    }.start()
    dialog.setOnDismissListener { wait.cancel() }
}
