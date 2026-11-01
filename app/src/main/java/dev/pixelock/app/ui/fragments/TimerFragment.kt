package dev.pixelock.app.ui.fragments

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.pixelock.app.R
import dev.pixelock.app.core.CrashGuard
import dev.pixelock.app.core.EndResult
import dev.pixelock.app.core.ServiceHealth
import dev.pixelock.app.core.SessionManager
import dev.pixelock.app.core.StrictModeController
import dev.pixelock.app.data.remote.OrderPurpose
import dev.pixelock.app.data.models.Settings
import dev.pixelock.app.databinding.FragmentTimerBinding
import dev.pixelock.app.domain.ADD_TIME_MIN
import dev.pixelock.app.domain.FocusSession
import dev.pixelock.app.domain.MAX_PAUSES
import dev.pixelock.app.domain.MAX_SESSION_MIN
import dev.pixelock.app.domain.PAUSE_MIN
import dev.pixelock.app.domain.isPaused
import dev.pixelock.app.ui.activity.PaymentActivity
import dev.pixelock.app.ui.askMinutes
import dev.pixelock.app.ui.askNewPassword
import dev.pixelock.app.ui.askSecret
import dev.pixelock.app.ui.confirmAfterWait
import dev.pixelock.app.ui.minuteLabel
import dev.pixelock.app.ui.onSettings
import dev.pixelock.app.ui.setUsable
import kotlinx.coroutines.launch

class TimerFragment : Fragment() {

    private companion object {
        const val PREFS = "timer"
        const val KEY_MINUTES = "minutes"
        const val CUSTOM_STEP = 5
        const val END_WAIT_SECONDS = 10
    }

    private var _binding: FragmentTimerBinding? = null
    private val binding get() = _binding!!

    private val ticker = Handler(Looper.getMainLooper())
    private var session: FocusSession? = null
    private var selected = 15
    private var enforcing = false
    private var settings = Settings()
    private lateinit var strict: StrictModeController

    private val tick = object : Runnable {
        override fun run() {
            render()
            if (session != null) ticker.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentTimerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        selected = prefs.getInt(KEY_MINUTES, 15)

        binding.preset15.setOnClickListener { select(15) }
        binding.preset45.setOnClickListener { select(45) }
        binding.preset180.setOnClickListener { select(180) }
        binding.presetCustom.setOnClickListener {
            askMinutes(R.string.custom, selected, 1..MAX_SESSION_MIN, ::select)
        }

        binding.ruler.apply {
            min = CUSTOM_STEP
            max = MAX_SESSION_MIN
            step = CUSTOM_STEP
            value = selected
            onChange = { select(it) }
        }
        binding.stepUp.setOnClickListener { select((selected + CUSTOM_STEP).coerceAtMost(MAX_SESSION_MIN)) }
        binding.stepDown.setOnClickListener { select((selected - CUSTOM_STEP).coerceAtLeast(CUSTOM_STEP)) }
        binding.slide.onCommit = ::onSlide
        binding.payEnd.setOnClickListener {
            startActivity(PaymentActivity.intent(requireContext(), OrderPurpose.END_TIMER, ""))
        }
        binding.pause.setOnClickListener { togglePause() }
        binding.addTime.text = getString(R.string.add_time, ADD_TIME_MIN)
        binding.addTime.setOnClickListener {
            val context = requireContext().applicationContext
            viewLifecycleOwner.lifecycleScope.launch { SessionManager.addTime(context) }
        }

        strict = StrictModeController(requireContext())
        onSettings(::onSettingsChanged)
        render()
    }

    private fun onSettingsChanged(s: Settings) {
        settings = s
        session = s.session?.takeIf { strict.isTimerRunning(s) }
        enforcing = strict.isEnforcing(s)
        ticker.removeCallbacks(tick)
        if (isVisible && session != null) tick.run() else render()
    }

    private fun select(minutes: Int) {
        selected = minutes
        binding.ruler.value = minutes
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_MINUTES, minutes).apply()
        render()
    }

    private fun onSlide() {
        when {
            session != null -> askToEnd()
            settings.endPasswordHash.isEmpty() -> askNewPassword(::start)
            else -> start()
        }
    }

    private fun start() {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch { SessionManager.start(context, selected) }
    }

    private fun togglePause() {
        val context = requireContext().applicationContext
        val paused = session?.isPaused(System.currentTimeMillis()) == true
        viewLifecycleOwner.lifecycleScope.launch {
            if (paused) SessionManager.resume(context) else SessionManager.pause(context)
        }
    }

    /** Password first, then a confirmation that makes you wait. Both must pass. */
    private fun askToEnd() {
        val context = requireContext().applicationContext
        if (settings.endPasswordHash.isEmpty()) {
            confirmEnd("")
            return
        }
        askSecret(R.string.end_password_t, getString(R.string.end_password_m)) { password ->
            viewLifecycleOwner.lifecycleScope.launch {
                if (SessionManager.passwordOk(context, password)) confirmEnd(password)
                else Toast.makeText(context, R.string.end_password_wrong, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun confirmEnd(password: String) {
        val context = requireContext().applicationContext
        confirmAfterWait(R.string.end_confirm_t, getString(R.string.end_confirm), END_WAIT_SECONDS) {
            viewLifecycleOwner.lifecycleScope.launch {
                when (SessionManager.endEarly(context, password)) {
                    EndResult.ENDED -> Unit
                    EndResult.STRICT -> Toast.makeText(context, R.string.strict_refused, Toast.LENGTH_SHORT).show()
                    EndResult.WRONG_PASSWORD -> Toast.makeText(context, R.string.end_password_wrong, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun render() {
        val b = _binding ?: return
        val now = System.currentTimeMillis()
        val active = session?.takeIf { strict.isTimerRunning(settings) }
        b.clock.setActive(active != null)
        b.clock.text = if (active != null) remaining(active) else minuteLabel(selected)

        val idle = active == null
        listOf(b.preset15, b.preset45, b.preset180, b.presetCustom, b.ruler, b.stepUp, b.stepDown)
            .forEach { it.setUsable(idle) }
        b.preset15.isSelected = selected == 15
        b.preset45.isSelected = selected == 45
        b.preset180.isSelected = selected == 180
        b.presetCustom.isSelected = selected !in setOf(15, 45, 180)
        b.customValue.text = minuteLabel(selected)

        b.slide.isEnabled = idle || !enforcing
        b.slide.text = getString(
            when {
                idle -> R.string.slide_start
                enforcing -> R.string.strict_running
                else -> R.string.slide_end
            }
        )
        b.payEnd.visibility = if (!idle && enforcing) View.VISIBLE else View.GONE
        b.runningRow.visibility = if (idle) View.GONE else View.VISIBLE
        if (active != null) renderPause(active, now)
        val working = ServiceHealth.isBlockerRunning(requireContext()) && !CrashGuard.isFailSafe(requireContext())
        b.warning.visibility = if (working) View.GONE else View.VISIBLE
    }

    private fun renderPause(active: FocusSession, now: Long) {
        val paused = active.isPaused(now)
        val left = MAX_PAUSES - active.pausesUsed
        binding.pause.setUsable(paused || left > 0)
        binding.pause.text = when {
            paused -> getString(R.string.pause_resume, clockText(active.pausedUntilMs - now))
            left > 0 -> getString(R.string.pause, PAUSE_MIN, left)
            else -> getString(R.string.pause_none)
        }
    }

    /** Stands still during a pause, because a pause gives its time back. */
    private fun remaining(session: FocusSession): String {
        val now = System.currentTimeMillis()
        return clockText(session.endsAtMs - if (session.isPaused(now)) session.pausedUntilMs else now)
    }

    private fun clockText(ms: Long): String {
        val seconds = (ms / 1000).coerceAtLeast(0)
        return if (seconds >= 3600) "%02d:%02d".format(seconds / 3600, seconds / 60 % 60)
        else "%02d:%02d".format(seconds / 60, seconds % 60)
    }

    override fun onResume() {
        super.onResume()
        if (!isHidden) tick.run()
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacks(tick)
    }

    override fun onHiddenChanged(hidden: Boolean) {
        ticker.removeCallbacks(tick)
        if (!hidden && isResumed) tick.run()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        ticker.removeCallbacks(tick)
        _binding = null
    }
}
