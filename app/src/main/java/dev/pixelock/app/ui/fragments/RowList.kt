package dev.pixelock.app.ui.fragments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.pixelock.app.R
import dev.pixelock.app.databinding.RowItemBinding

private const val PANEL = "panel"

/** Adds a plain tappable row. Settings, setup and developer screens are lists of these. */
fun LinearLayout.addRow(
    title: String,
    desc: String?,
    onClick: (() -> Unit)? = null
): RowItemBinding {
    val row = RowItemBinding.inflate(LayoutInflater.from(context), this, false)
    row.title.text = title
    row.desc.text = desc
    row.desc.visibility = if (desc.isNullOrEmpty()) View.GONE else View.VISIBLE
    if (onClick != null) row.root.setOnClickListener { onClick() } else row.root.isClickable = false
    // The same slot on a panel that the main tabs use. Rows outside a panel stay tiles.
    if (tag == PANEL) row.root.setBackgroundResource(R.drawable.bg_slot)
    addView(row.root)
    return row
}

/** A row that opens another page. */
fun LinearLayout.addPageRow(title: String, desc: String?, onClick: () -> Unit): RowItemBinding =
    addRow(title, desc, onClick).also { it.chevron.visibility = View.VISIBLE }

/** A panel of rows, as on the main tabs. Add rows to the panel this returns. */
fun LinearLayout.addGroup(heading: String? = null): LinearLayout {
    heading?.let(::addHeading)
    val panel = LinearLayout(context)
    panel.tag = PANEL
    panel.orientation = LinearLayout.VERTICAL
    panel.setBackgroundResource(R.drawable.bg_group)
    panel.setPadding(dp(12), dp(12), dp(12), dp(4))
    addView(panel)
    (panel.layoutParams as LinearLayout.LayoutParams).bottomMargin = dp(8)
    return panel
}

/** Text outside the tiles. With [onClick] it reads as a link and keeps a full size touch target. */
fun LinearLayout.addNote(text: String, onClick: (() -> Unit)? = null): TextView {
    val note = TextView(context)
    note.setTextAppearance(R.style.Text_Label)
    note.text = text
    val edge = dp(6)
    note.setPadding(edge, dp(4), edge, dp(if (onClick == null) 12 else 16))
    onClick?.let { open ->
        note.setTextColor(context.getColor(R.color.ink))
        note.minHeight = dp(48)
        note.setOnClickListener { open() }
    }
    addView(note)
    return note
}

fun LinearLayout.addHeading(text: String) {
    val heading = TextView(context)
    heading.setTextAppearance(R.style.Text_Caps)
    heading.text = text
    heading.isAccessibilityHeading = true
    heading.setPadding(dp(6), dp(12), dp(6), dp(8))
    addView(heading)
}

fun LinearLayout.addButton(text: String, onClick: () -> Unit) {
    val button = MaterialButton(context)
    button.text = text
    button.minHeight = dp(56)
    button.setOnClickListener { onClick() }
    addView(button)
    (button.layoutParams as LinearLayout.LayoutParams).apply {
        width = ViewGroup.LayoutParams.MATCH_PARENT
        topMargin = dp(16)
    }
}

private fun View.dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
