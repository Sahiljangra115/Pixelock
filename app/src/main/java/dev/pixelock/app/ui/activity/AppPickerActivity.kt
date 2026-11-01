package dev.pixelock.app.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import dev.pixelock.app.core.InstalledApps
import dev.pixelock.app.core.InstalledApps.App
import dev.pixelock.app.databinding.ActivityPickerBinding
import dev.pixelock.app.databinding.ItemAppBinding
import kotlinx.coroutines.launch

/** Picks apps for a list. Always allowed apps never appear, so they cannot be picked by mistake. */
class AppPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED = "selected"

        fun intent(context: Context, selected: List<String>): Intent =
            Intent(context, AppPickerActivity::class.java)
                .putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected))
    }

    private lateinit var binding: ActivityPickerBinding
    private val selected = LinkedHashSet<String>()
    private var all: List<App> = emptyList()

    private val adapter = object : ListAdapter<App, RecyclerView.ViewHolder>(
        object : DiffUtil.ItemCallback<App>() {
            override fun areItemsTheSame(a: App, b: App) = a.pkg == b.pkg
            override fun areContentsTheSame(a: App, b: App) = a.label == b.label
        }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val row = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return object : RecyclerView.ViewHolder(row.root) {}.also { it.itemView.tag = row }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val app = getItem(position)
            val row = holder.itemView.tag as ItemAppBinding
            showIcon(row, app)
            row.name.text = app.label
            row.check.isChecked = app.pkg in selected
            row.root.setOnClickListener {
                if (!selected.remove(app.pkg)) selected += app.pkg
                row.check.isChecked = app.pkg in selected
            }
        }
    }

    /** The row may be reused by the time the icon is ready, so the tag decides who gets it. */
    private fun showIcon(row: ItemAppBinding, app: App) {
        row.icon.tag = app.pkg
        val loaded = InstalledApps.loadedIcon(app)
        row.icon.setImageDrawable(loaded)
        if (loaded != null) return
        lifecycleScope.launch {
            val icon = InstalledApps.icon(this@AppPickerActivity, app)
            if (row.icon.tag == app.pkg) row.icon.setImageDrawable(icon)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { root, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            root.setPadding(bars.left + root.paddingStart, bars.top, bars.right + root.paddingEnd, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        selected += intent.getStringArrayListExtra(EXTRA_SELECTED).orEmpty()

        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.search.doAfterTextChanged { filter(it?.toString().orEmpty()) }
        binding.save.setOnClickListener {
            // Selected apps that are not installed stay in the list, ready for when they are.
            setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected)))
            finish()
        }

        // The cached list shows at once. The fresh read then adds anything installed since.
        all = InstalledApps.cached
        filter("")
        binding.loading.visibility = if (all.isEmpty()) View.VISIBLE else View.GONE
        lifecycleScope.launch {
            all = InstalledApps.refresh(this@AppPickerActivity)
            binding.loading.visibility = View.GONE
            filter(binding.search.text?.toString().orEmpty())
        }
    }

    private fun filter(query: String) {
        adapter.submitList(all.filter { it.label.contains(query, ignoreCase = true) })
    }
}
