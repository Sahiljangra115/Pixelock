package dev.pixelock.app.ui.activity

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.Data
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
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
import dev.pixelock.app.R
import dev.pixelock.app.databinding.ActivityPickerBinding
import dev.pixelock.app.databinding.ItemAppBinding
import dev.pixelock.app.domain.GROUP_MAX
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Picks phone numbers and emails to invite. Contacts stay in memory on this screen. Picked ones
 * go only to [dev.pixelock.app.core.SmsInvite] and the phone's own message and mail apps, and
 * never reach the backend.
 */
class ContactPickerActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_SELECTED = "selected"

        fun intent(context: Context) = Intent(context, ContactPickerActivity::class.java)
    }

    private data class Contact(val name: String, val address: String)

    private lateinit var binding: ActivityPickerBinding
    private val selected = LinkedHashSet<String>()
    private var all: List<Contact> = emptyList()

    private val adapter = object : ListAdapter<Contact, RecyclerView.ViewHolder>(
        object : DiffUtil.ItemCallback<Contact>() {
            override fun areItemsTheSame(a: Contact, b: Contact) = a.address == b.address
            override fun areContentsTheSame(a: Contact, b: Contact) = a == b
        }
    ) {
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): RecyclerView.ViewHolder {
            val row = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            row.icon.visibility = View.GONE
            return object : RecyclerView.ViewHolder(row.root) {}.also { it.itemView.tag = row }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val contact = getItem(position)
            val row = holder.itemView.tag as ItemAppBinding
            row.name.text = "${contact.name}\n${contact.address}"
            row.check.isChecked = contact.address in selected
            row.root.setOnClickListener {
                if (!selected.remove(contact.address)) {
                    if (selected.size < GROUP_MAX) selected += contact.address
                    else Toast.makeText(this@ContactPickerActivity, R.string.contacts_limit, Toast.LENGTH_LONG).show()
                }
                row.check.isChecked = contact.address in selected
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

        binding.search.setHint(R.string.contacts_search)
        binding.save.setText(R.string.contacts_send)
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
        binding.search.doAfterTextChanged { filter(it?.toString().orEmpty()) }
        binding.save.setOnClickListener {
            setResult(RESULT_OK, Intent().putStringArrayListExtra(EXTRA_SELECTED, ArrayList(selected)))
            finish()
        }

        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) { load() }
            if (all.isEmpty()) Toast.makeText(this@ContactPickerActivity, R.string.contacts_empty, Toast.LENGTH_LONG).show()
            filter(binding.search.text?.toString().orEmpty())
        }
    }

    /** One row per number or email, since those are what an invite goes to. */
    private fun load(): List<Contact> {
        val rows = ArrayList<Contact>()
        contentResolver.query(
            Data.CONTENT_URI,
            arrayOf(Data.DISPLAY_NAME, Data.DATA1),
            "${Data.MIMETYPE} IN (?, ?)",
            arrayOf(Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE),
            "${Data.DISPLAY_NAME} COLLATE NOCASE"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val address = cursor.getString(1)?.trim().orEmpty()
                if (address.isNotEmpty()) rows += Contact(cursor.getString(0).orEmpty(), address)
            }
        }
        return rows.distinctBy { it.address.filterNot(Char::isWhitespace) }
    }

    private fun filter(query: String) {
        adapter.submitList(
            all.filter { it.name.contains(query, ignoreCase = true) || it.address.contains(query, ignoreCase = true) }
        )
    }
}
