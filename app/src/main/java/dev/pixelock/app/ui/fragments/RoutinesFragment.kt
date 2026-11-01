package dev.pixelock.app.ui.fragments

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import dev.pixelock.app.R
import dev.pixelock.app.core.StrictModeController
import dev.pixelock.app.data.models.Settings
import dev.pixelock.app.databinding.FragmentRoutinesBinding
import dev.pixelock.app.domain.MINUTES_PER_DAY
import dev.pixelock.app.domain.Routine
import dev.pixelock.app.domain.activeEndMs
import dev.pixelock.app.domain.canEdit
import dev.pixelock.app.ui.askTime
import dev.pixelock.app.ui.durationLabel
import dev.pixelock.app.ui.minuteLabel
import dev.pixelock.app.ui.onSettings
import dev.pixelock.app.ui.updateSettings
import dev.pixelock.app.ui.views.PillarToggle
import java.time.ZonedDateTime

class RoutinesFragment : Fragment() {

    private var _binding: FragmentRoutinesBinding? = null
    private val binding get() = _binding!!

    private var settings = Settings()
    private var open = 0
    private var enforcing = false
    private var timerRunning = false
    private val tabViews = ArrayList<LinearLayout>()
    private val pillars = ArrayList<PillarToggle>()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentRoutinesBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        buildTabs()
        buildDays()
        binding.startRuler.apply { min = 0; max = MINUTES_PER_DAY; step = 15; commitOnRelease = true }
        binding.endRuler.apply { min = 0; max = MINUTES_PER_DAY; step = 15; commitOnRelease = true }
        binding.startRuler.onChange = ::setStart
        binding.endRuler.onChange = ::setEnd
        binding.startRuler.onScrub = { v -> showRange(start = v) }
        binding.endRuler.onScrub = { v -> showRange(end = v) }
        binding.startValue.setOnClickListener { type(R.string.start, routine().startMin, ::setStart) }
        binding.endValue.setOnClickListener { type(R.string.end, routine().endMin, ::setEnd) }
        binding.lock.setOnClickListener { change { it.copy(locked = !it.locked) } }
        binding.enable.setOnClickListener { change { it.copy(enabled = !it.enabled) } }

        val strict = StrictModeController(requireContext())
        onSettings { s ->
            settings = s
            enforcing = strict.isEnforcing(s)
            timerRunning = strict.isTimerRunning(s)
            render()
        }
    }

    private fun buildTabs() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        for (i in 0 until 3) {
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_tile)
                isClickable = true
                isFocusable = true
                setOnClickListener { open = i; render() }
            }
            tab.addView(TextView(context).apply {
                text = getString(R.string.routine_name, i + 1)
                textSize = 11f
                gravity = Gravity.CENTER
                duplicateParentStateEnabled()
                setTextColor(context.getColorStateList(R.color.tile_sub))
            })
            tab.addView(TextView(context).apply {
                textSize = 19f
                gravity = Gravity.CENTER
                duplicateParentStateEnabled()
                setTextColor(context.getColorStateList(R.color.tile_text))
            })
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            if (i > 0) params.marginStart = (6 * density).toInt()
            binding.routineTabs.addView(tab, params)
            tabViews += tab
        }
    }

    private fun TextView.duplicateParentStateEnabled() {
        isDuplicateParentStateEnabled = true
    }

    private fun buildDays() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        val letters = getString(R.string.day_letters)
        val names = java.time.DayOfWeek.values()
        for (day in 0 until 7) {
            val letter = TextView(context).apply {
                text = letters[day].toString()
                gravity = Gravity.CENTER
                setTextColor(context.getColor(R.color.ink_soft))
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            binding.dayLetters.addView(
                letter,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            )
            val pillar = PillarToggle(context).apply {
                contentDescription = names[(day + 6) % 7].name.lowercase().replaceFirstChar(Char::titlecase)
                onToggle = { on -> toggleDay(day, on) }
            }
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            params.marginStart = (3 * density).toInt()
            params.marginEnd = (3 * density).toInt()
            binding.dayPillars.addView(pillar, params)
            pillars += pillar
        }
    }

    private fun toggleDay(day: Int, on: Boolean) {
        change { it.copy(days = if (on) it.days + day else it.days - day) }
    }

    private fun routine(): Routine = settings.routines[open]

    private fun setStart(value: Int) = change { it.copy(startMin = value) }

    private fun setEnd(value: Int) = change { it.copy(endMin = value) }

    private fun type(title: Int, minutes: Int, onDone: (Int) -> Unit) {
        if (editable()) askTime(title, minutes, onDone) else refuse()
    }

    private fun editable() = canEdit(routine().locked, timerRunning, enforcing)

    private fun refuse() {
        Toast.makeText(requireContext(), R.string.edit_refused, Toast.LENGTH_SHORT).show()
    }

    private fun change(edit: (Routine) -> Routine) {
        if (!editable()) {
            refuse()
            render()
            return
        }
        val id = routine().id
        updateSettings { s -> s.copy(routines = s.routines.map { if (it.id == id) edit(it) else it }) }
    }

    private fun render() {
        val b = _binding ?: return
        val routine = routine()
        val now = ZonedDateTime.now()
        val endMs = routine.activeEndMs(now)
        b.rangeClock.setActive(endMs != null)

        val length = length(routine.startMin, routine.endMin)
        b.strip.progress = if (endMs == null || length == 0) 0f else {
            1f - (endMs - System.currentTimeMillis()) / (length * 60_000f)
        }

        tabViews.forEachIndexed { i, tab ->
            tab.isSelected = i == open
            (tab.getChildAt(1) as TextView).text = minuteLabel(settings.routines[i].startMin)
        }
        pillars.forEachIndexed { day, pillar -> pillar.checked = day in routine.days }
        b.startRuler.value = routine.startMin
        b.endRuler.value = routine.endMin
        b.lock.setImageResource(if (routine.locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
        b.enable.isSelected = routine.enabled
        b.enable.text = getString(if (routine.enabled) R.string.routine_on else R.string.routine_off)
        showRange()
    }

    private fun length(start: Int, end: Int): Int =
        ((end - start + MINUTES_PER_DAY) % MINUTES_PER_DAY)
            .let { if (it == 0 && start != end) MINUTES_PER_DAY else it }

    /** Also runs per mark while a tape moves. Arguments hold what the finger has not saved yet. */
    private fun showRange(start: Int = routine().startMin, end: Int = routine().endMin) {
        val b = _binding ?: return
        b.rangeClock.text = "${minuteLabel(start)}  ${minuteLabel(end)}"
        (tabViews[open].getChildAt(1) as TextView).text = minuteLabel(start)
        b.startValue.text = getString(R.string.routine_start_value, minuteLabel(start))
        b.endValue.text = getString(R.string.routine_end_value, minuteLabel(end))
        val state = getString(if (routine().enabled) R.string.routine_on else R.string.routine_off)
        b.lengthLabel.text = getString(
            R.string.stats_caption_plain, state, requireContext().durationLabel(length(start, end))
        )
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tabViews.clear()
        pillars.clear()
        _binding = null
    }
}
