package dev.pixelock.app.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import dev.pixelock.app.R

private val DIGITS = arrayOf(
    "111101101101111", "010010010010010", "111001111100111", "111001111001111", "101101111001001",
    "111100111001111", "111100111101111", "111001001001001", "111101111101111", "111101111001111"
)

/** Clock digits drawn as rects from a table, so a tick costs no font work and no allocation. */
class PixelClockView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val onPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.digit) }
    private val dotPaint = Paint().apply {
        color = ContextCompat.getColor(context, R.color.digit)
        alpha = 150
    }

    var text: String = ""
        set(value) {
            if (field == value) return
            field = value
            contentDescription = value
            invalidate()
        }

    /** Idle times are grey, running times are colored. */
    fun setActive(active: Boolean) {
        val color = ContextCompat.getColor(context, if (active) R.color.digit else R.color.digit_idle)
        if (onPaint.color == color) return
        onPaint.color = color
        dotPaint.color = color
        dotPaint.alpha = 150
        invalidate()
    }

    private fun columns(ch: Char) = when (ch) {
        ':' -> 2
        ' ' -> 3
        else -> 4
    }

    override fun onDraw(canvas: Canvas) {
        var cols = -1
        for (ch in text) cols += columns(ch)
        if (cols <= 0) return
        val cell = minOf((width - paddingLeft - paddingRight) / cols, (height - paddingTop - paddingBottom) / 5)
        if (cell <= 0) return
        var x = (width - cols * cell) / 2
        val y = (height - 5 * cell) / 2
        for (ch in text) {
            if (ch == ':') {
                val c = cell.toFloat()
                canvas.drawRect(x.toFloat(), y + c, x + c, y + 2 * c, dotPaint)
                canvas.drawRect(x.toFloat(), y + 3 * c, x + c, y + 4 * c, dotPaint)
            } else if (ch in '0'..'9') {
                val rows = DIGITS[ch - '0']
                for (i in rows.indices) {
                    if (rows[i] != '1') continue
                    val left = (x + (i % 3) * cell).toFloat()
                    val top = (y + (i / 3) * cell).toFloat()
                    canvas.drawRect(left, top, left + cell, top + cell, onPaint)
                }
            }
            x += columns(ch) * cell
        }
    }
}

/** The word mark. One block turns orange to show the open tab. */
class PixelLogoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        val GLYPHS = arrayOf(
            arrayOf("111", "101", "111", "100", "100"),
            arrayOf("1", "0", "1", "1", "1"),
            arrayOf("101", "101", "010", "101", "101"),
            arrayOf("111", "101", "111", "100", "111"),
            arrayOf("1", "1", "1", "1", "1"),
            arrayOf("111", "101", "101", "101", "111"),
            arrayOf("111", "100", "100", "100", "111"),
            arrayOf("101", "101", "110", "101", "101")
        )
    }

    private val ink = Paint().apply { color = ContextCompat.getColor(context, R.color.ink) }
    private val accent = Paint().apply { color = ContextCompat.getColor(context, R.color.accent) }

    /** Index of the letter whose first block is orange, or -1 for none. */
    var accentGlyph: Int = -1
        set(value) {
            field = value
            invalidate()
        }

    init {
        contentDescription = context.getString(R.string.app_name_plain)
    }

    override fun onDraw(canvas: Canvas) {
        val cols = GLYPHS.sumOf { it[0].length + 1 } - 1
        val cell = minOf(width / cols, height / 5)
        if (cell <= 0) return
        var x = (width - cols * cell) / 2
        val y = (height - 5 * cell) / 2
        for (g in GLYPHS.indices) {
            val rows = GLYPHS[g]
            var first = g == accentGlyph
            for (r in rows.indices) for (c in rows[r].indices) {
                if (rows[r][c] != '1') continue
                val left = (x + c * cell).toFloat()
                val top = (y + r * cell).toFloat()
                canvas.drawRect(left, top, left + cell, top + cell, if (first) accent else ink)
                first = false
            }
            x += (rows[0].length + 1) * cell
        }
    }
}

/** A row of small squares showing progress, as on the routine card. */
class PixelStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        const val COUNT = 32
    }

    private val on = Paint().apply { color = ContextCompat.getColor(context, R.color.accent_pale) }
    private val off = Paint().apply { color = ContextCompat.getColor(context, R.color.ink_faint) }

    var progress: Float = 0f
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val pitch = width / COUNT
        val size = (pitch * 3 / 4).toFloat()
        val filled = (progress * COUNT).toInt().coerceIn(0, COUNT)
        for (i in 0 until COUNT) {
            val left = (i * pitch).toFloat()
            canvas.drawRect(left, 0f, left + size, size, if (i < filled) on else off)
        }
    }
}
