package dev.pixelock.app.ui.activity

import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
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
import dev.pixelock.app.core.DeviceAllowList
import dev.pixelock.app.databinding.ActivityPickerBinding
import dev.pixelock.app.databinding.ItemAppBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Picks apps for a list. Always allowed apps never appear, so they cannot be picked by mistake. */
class AppPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED = "selected"

        fun intent(context: Context, selected: List<String>): Intent =
            Intent(context, AppPickerActivity::class.java)
                .putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected))
    }

    private data class App(val pkg: String, val label: String, val icon: Drawable)

    private lateinit var binding: ActivityPickerBinding
    private val selected = LinkedHashSet<String>()
    private var all: List<App> = emptyList()

    private val adapter = object : ListAdapter<App, RecyclerView.ViewHolder>(
        object : DiffUtil.ItemCallback<App>() {
            override fun areItemsTheSame(a: App, b: App) = a.pkg == b.pkg
            override fun areContentsTheSame(a: App, b: App) = a.pkg == b.pkg
        }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val row = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return object : RecyclerView.ViewHolder(row.root) {}.also { it.itemView.tag = row }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val app = getItem(position)
            val row = holder.itemView.tag as ItemAppBinding
            row.icon.setImageDrawable(app.icon)
            row.name.text = app.label
            row.check.isChecked = app.pkg in selected
            row.root.setOnClickListener {
                if (!selected.remove(app.pkg)) selected += app.pkg
                row.check.isChecked = app.pkg in selected
            }
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

        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) { loadApps() }
            filter(binding.search.text?.toString().orEmpty())
        }
    }

    private fun loadApps(): List<App> {
        val blockedOut = DeviceAllowList.resolve(this)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packageManager.queryIntentActivities(launcher, 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName !in blockedOut }
            .map { App(it.activityInfo.packageName, it.loadLabel(packageManager).toString(), it.loadIcon(packageManager)) }
            .sortedBy { it.label.lowercase() }
    }

    private fun filter(query: String) {
        adapter.submitList(all.filter { it.label.contains(query, ignoreCase = true) })
    }
}
