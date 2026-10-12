package dev.pixelock.app.ui.fragments

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import dev.pixelock.app.R
import dev.pixelock.app.core.StrictModeController
import dev.pixelock.app.data.models.Settings
import dev.pixelock.app.databinding.FragmentBagsBinding
import dev.pixelock.app.domain.Bag
import dev.pixelock.app.domain.BagMode
import dev.pixelock.app.domain.MINUTES_PER_DAY
import dev.pixelock.app.domain.canEdit
import dev.pixelock.app.ui.activity.AppPickerActivity
import dev.pixelock.app.ui.minuteLabel
import dev.pixelock.app.ui.onSettings
import dev.pixelock.app.ui.updateSettings

class BagsFragment : Fragment() {

    private var _binding: FragmentBagsBinding? = null
    private val binding get() = _binding!!

    private var settings = Settings()
    private var open = 0
    private var enforcing = false
    private var timerRunning = false
    private val tabs = ArrayList<TextView>()
    private val addButtons = ArrayList<TextView>()
    private val lockButtons = ArrayList<ImageButton>()

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
        val picked = result.data?.getStringArrayListExtra(AppPickerActivity.EXTRA_SELECTED)
            ?: return@registerForActivityResult
        change { it.copy(packages = picked) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentBagsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        buildColumns()
        binding.modeLimit.setOnClickListener { change { it.copy(mode = BagMode.DAILY_LIMIT) } }
        binding.modeWindow.setOnClickListener { change { it.copy(mode = BagMode.WINDOW) } }
        binding.rulerA.onChange = { value ->
            if (bag().mode == BagMode.DAILY_LIMIT) change { it.copy(dailyLimitMin = value) }
            else change { it.copy(startMin = value) }
        }
        binding.rulerB.onChange = { value -> change { it.copy(endMin = value) } }
        binding.rulerA.commitOnRelease = true
        binding.rulerB.commitOnRelease = true

        val strict = StrictModeController(requireContext())
        onSettings { s ->
            settings = s
            enforcing = strict.isEnforcing(s)
            timerRunning = strict.isTimerRunning(s)
            render()
        }
    }

    private fun bag(): Bag = settings.bags[open]

    private fun buildColumns() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        for (i in 0 until 3) {
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            val params = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            if (i > 0) params.marginStart = (8 * density).toInt()

            val tab = TextView(context).apply {
                text = getString(R.string.bag_name, i + 1)
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_bag_tab)
                setTextColor(context.getColorStateList(R.color.bag_tab_text))
                minHeight = (48 * density).toInt()
                setOnClickListener { open = i; render() }
            }
            val add = TextView(context).apply {
                gravity = Gravity.CENTER
                setBackgroundResource(R.drawable.bg_slot)
                setTextColor(context.getColor(R.color.ink))
                textSize = 15f
                contentDescription = getString(R.string.bag_add, i + 1)
                minHeight = (64 * density).toInt()
                isClickable = true
                setOnClickListener { pick(i) }
            }
            val lock = ImageButton(context).apply {
                setBackgroundResource(R.drawable.bg_slot)
                contentDescription = getString(R.string.bag_lock, i + 1)
                minimumHeight = (56 * density).toInt()
                setOnClickListener { toggleLock(i) }
            }
            val gap = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = (8 * density).toInt() }
            column.addView(tab)
            column.addView(add, gap)
            column.addView(lock, gap)
            binding.bagColumns.addView(column, params)
            tabs += tab
            addButtons += add
            lockButtons += lock
        }
    }

    private fun editable(bag: Bag): Boolean = canEdit(
        bag.locked,
        timerRunning,
        enforcing
    )

    private fun refuse() {
        Toast.makeText(requireContext(), R.string.edit_refused, Toast.LENGTH_SHORT).show()
    }

    private fun pick(index: Int) {
        val target = settings.bags[index]
        if (!editable(target)) return refuse()
        open = index
        render()
        picker.launch(AppPickerActivity.intent(requireContext(), target.packages))
    }

    private fun toggleLock(index: Int) {
        val target = settings.bags[index]
        if (!editable(target)) return refuse()
        open = index
        change { it.copy(locked = !it.locked) }
    }

    private fun change(edit: (Bag) -> Bag) {
        val target = bag()
        if (!editable(target)) {
            refuse()
            render()
            return
        }
        val id = target.id
        updateSettings { s -> s.copy(bags = s.bags.map { if (it.id == id) edit(it) else it }) }
    }

    private fun render() {
        val b = _binding ?: return
        val bags = settings.bags
        b.art.selected = open
        b.art.appCounts = IntArray(3) { bags[it].packages.size }
        for (i in 0 until 3) {
            tabs[i].isSelected = i == open
            val count = bags[i].packages.size
            addButtons[i].text = if (count == 0) "" else resources.getQuantityString(R.plurals.app_count, count, count)
            addButtons[i].setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, if (count == 0) R.drawable.ic_plus else 0, 0, 0
            )
            lockButtons[i].setImageResource(if (bags[i].locked) R.drawable.ic_lock else R.drawable.ic_lock_open)
            lockButtons[i].isSelected = false
        }
        val bag = bags[open]
        val limit = bag.mode == BagMode.DAILY_LIMIT
        b.modeLimit.isSelected = limit
        b.modeWindow.isSelected = !limit
        b.rulerB.visibility = if (limit) View.GONE else View.VISIBLE
        b.rulerA.apply {
            if (limit) {
                min = 0; max = 180; step = 5
                value = bag.dailyLimitMin
                contentDescription = getString(R.string.bag_limit)
            } else {
                min = 0; max = MINUTES_PER_DAY; step = 15
                value = bag.startMin
                contentDescription = getString(R.string.bag_open_from)
            }
        }
        b.rulerB.apply {
            min = 0; max = MINUTES_PER_DAY; step = 15
            value = bag.endMin
            contentDescription = getString(R.string.bag_open_to)
        }
        b.valueLabel.text = if (limit) {
            getString(R.string.bag_limit_value, bag.dailyLimitMin)
        } else {
            getString(R.string.stats_range, minuteLabel(bag.startMin), minuteLabel(bag.endMin))
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tabs.clear()
        addButtons.clear()
        lockButtons.clear()
        _binding = null
    }
}
