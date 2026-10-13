package dev.pixelock.app.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import dev.pixelock.app.R
import dev.pixelock.app.data.db.AppDatabase
import dev.pixelock.app.databinding.FragmentStatsDetailBinding
import dev.pixelock.app.databinding.ViewStatTileBinding
import dev.pixelock.app.domain.summarize
import dev.pixelock.app.ui.durationLabel
import kotlinx.coroutines.launch
import java.time.LocalDate

class StatsDetailFragment : Fragment() {

    companion object {
        private const val ARG_MODE = "mode"
        private const val ARG_ANCHOR = "anchor"

        fun create(period: StatsPeriod) = StatsDetailFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_MODE, period.mode.name)
                putString(ARG_ANCHOR, period.anchor.toString())
            }
        }
    }

    private var _binding: FragmentStatsDetailBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, state: Bundle?): View {
        _binding = FragmentStatsDetailBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        val args = requireArguments()
        val period = StatsPeriod(
            StatsMode.valueOf(args.getString(ARG_MODE)!!),
            LocalDate.parse(args.getString(ARG_ANCHOR)!!)
        )
        binding.back.setOnClickListener { parentFragmentManager.popBackStack() }
        binding.period.text = getString(R.string.stats_range, period.from.toString(), period.to.toString())

        val dao = AppDatabase.getInstance(requireContext()).dayTotalDao()
        viewLifecycleOwner.lifecycleScope.launch {
            val days = dao.range(period.from.toString(), period.to.toString()).map { it.toDayValue() }
            val summary = summarize(days, LocalDate.now())
            val columns = period.columns(days)
            val b = _binding ?: return@launch
            b.towers.values = IntArray(columns.size) { columns[it].blocks }
            val hasBlocks = columns.any { it.blocks > 0 }
            b.towers.visibility = if (hasBlocks) View.VISIBLE else View.INVISIBLE
            b.empty.visibility = if (hasBlocks) View.GONE else View.VISIBLE

            val context = requireContext()
            b.tileBest.set(R.string.best_day, context.durationLabel(summary.bestDayMin))
            b.tileTotal.set(R.string.total_time, context.durationLabel(summary.totalMin))
            b.tileAvg.set(R.string.avg_day, summary.avgMinPerDay.toString())
            b.tileTower.set(R.string.tallest_tower, summary.tallestTower.toString())
            b.tileTower.value.setTextColor(context.getColor(R.color.digit))
        }
    }

    private fun ViewStatTileBinding.set(label: Int, value: String) {
        this.label.setText(label)
        this.value.text = value
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
