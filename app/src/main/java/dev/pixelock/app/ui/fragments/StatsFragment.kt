package dev.pixelock.app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.pixelock.app.R
import dev.pixelock.app.data.db.AppDatabase
import dev.pixelock.app.data.db.DayTotalEntity
import dev.pixelock.app.databinding.FragmentStatsBinding
import dev.pixelock.app.domain.DayValue
import dev.pixelock.app.ui.activity.MainActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun DayTotalEntity.toDayValue() = DayValue(LocalDate.parse(date), focusedMin, distractedMin, blocks)

class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    private var period = StatsPeriod(StatsMode.MONTH, LocalDate.now())
    private var columns: List<DayValue> = emptyList()
    private var picked = 0
    private var loadJob: Job? = null

    private val monthYear = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault())
    private val shortDay = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    private val dayName = DateTimeFormatter.ofPattern("EEEE d", Locale.getDefault())
    private val monthName = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        binding.modeDay.setOnClickListener { setPeriod(period.copy(mode = StatsMode.DAY)) }
        binding.modeMonth.setOnClickListener { setPeriod(period.copy(mode = StatsMode.MONTH)) }
        binding.modeYear.setOnClickListener { setPeriod(period.copy(mode = StatsMode.YEAR)) }
        binding.prev.setOnClickListener { setPeriod(period.shifted(-1)) }
        binding.next.setOnClickListener { setPeriod(period.shifted(1)) }
        binding.towers.onPick = { pick(it) }
        binding.caption.setOnClickListener { openDetail() }
        binding.dial.onChange = { pick(it) }
        setPeriod(period)
    }

    private fun openDetail() {
        (activity as? MainActivity)?.openOverlay(StatsDetailFragment.create(period))
    }

    private fun setPeriod(next: StatsPeriod) {
        period = next
        val b = binding
        b.modeDay.isSelected = next.mode == StatsMode.DAY
        b.modeMonth.isSelected = next.mode == StatsMode.MONTH
        b.modeYear.isSelected = next.mode == StatsMode.YEAR
        b.period.text = when (next.mode) {
            StatsMode.DAY -> getString(R.string.stats_range, next.from.format(shortDay), next.to.format(shortDay))
            StatsMode.MONTH -> next.anchor.format(monthYear)
            StatsMode.YEAR -> next.anchor.year.toString()
        }
        b.next.isEnabled = next.to.isBefore(LocalDate.now())
        b.next.alpha = if (b.next.isEnabled) 1f else 0.3f

        loadJob?.cancel()
        val dao = AppDatabase.getInstance(requireContext()).dayTotalDao()
        val owner = viewLifecycleOwner
        loadJob = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                dao.observeRange(next.from.toString(), next.to.toString()).collect { rows ->
                    show(next, rows.map { it.toDayValue() })
                }
            }
        }
    }

    private fun show(shown: StatsPeriod, days: List<DayValue>) {
        val b = _binding ?: return
        columns = shown.columns(days)
        val today = LocalDate.now()
        val todayIndex = columns.indexOfLast { !it.date.isAfter(today) }
        picked = todayIndex.coerceAtLeast(0)
        b.towers.values = IntArray(columns.size) { columns[it].focusedMin }
        b.towers.futureFrom = if (todayIndex < 0) 0 else todayIndex + 1
        b.dial.count = columns.size
        pick(picked)
    }

    private fun pick(index: Int) {
        val b = _binding ?: return
        val column = columns.getOrNull(index) ?: return
        picked = index
        b.towers.selected = index
        b.dial.index = index
        val name = (if (period.mode == StatsMode.YEAR) column.date.format(monthName) else column.date.format(dayName))
        b.caption.text = getString(R.string.stats_caption, name, column.focusedMin)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
