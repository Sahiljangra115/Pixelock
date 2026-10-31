package dev.pixelock.app.ui.fragments

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import dev.pixelock.app.R
import dev.pixelock.app.databinding.RowItemBinding

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
    addView(row.root)
    return row
}

/** Text outside the tiles. With [onClick] it reads as a link and keeps a full size touch target. */
fun LinearLayout.addNote(text: String, onClick: (() -> Unit)? = null): TextView {
    val note = TextView(context)
    note.setTextAppearance(R.style.Text_Label)
    note.text = text
    val edge = dp(18)
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
    heading.setPadding(dp(18), dp(16), dp(18), dp(8))
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
