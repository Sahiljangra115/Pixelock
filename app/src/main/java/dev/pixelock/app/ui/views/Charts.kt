package dev.pixelock.app.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import dev.pixelock.app.R

/** The three bags. The open bag is orange. Squares under a bag show how many apps it holds. */
class BagsArtView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val ink = Paint().apply { color = ContextCompat.getColor(context, R.color.ink_soft) }
    private val accent = Paint().apply { color = ContextCompat.getColor(context, R.color.accent) }
    private val hole = Paint().apply { color = ContextCompat.getColor(context, R.color.card) }
    private val empty = Paint().apply { color = ContextCompat.getColor(context, R.color.ink_faint) }

    var selected = 0
        set(value) {
            field = value
            invalidate()
        }
    var appCounts = intArrayOf(0, 0, 0)
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val unit = minOf(width / 34, height / 13).toFloat()
        if (unit <= 0) return
        val startX = (width - 34 * unit) / 2
        val startY = (height - 13 * unit) / 2
        for (bag in 0 until 3) {
            val x = startX + bag * 12 * unit
            val paint = if (bag == selected) accent else ink
            canvas.drawRect(x, startY + 2 * unit, x + 3 * unit, startY + 9 * unit, paint)
            canvas.drawRect(x + 3.5f * unit, startY, x + 6.5f * unit, startY + 9 * unit, paint)
            canvas.drawRect(x + 7 * unit, startY + 2 * unit, x + 10 * unit, startY + 9 * unit, paint)
            canvas.drawRect(x + 4 * unit, startY + unit, x + 6 * unit, startY + 1.6f * unit, hole)
            for (slot in 0 until 3) {
                val left = x + 0.75f + slot * 3.4f * unit
                val on = slot < appCounts[bag]
                canvas.drawRect(left, startY + 10 * unit, left + 2.6f * unit, startY + 12.6f * unit, if (on) paint else empty)
            }
        }
    }
}

/** Towers of blocks. Each column is a day or a month. The picked column is orange. */
class TowerStripView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val ink = Paint().apply { color = ContextCompat.getColor(context, R.color.ink) }
    private val accent = Paint().apply { color = ContextCompat.getColor(context, R.color.accent) }
    private val faint = Paint().apply { color = ContextCompat.getColor(context, R.color.ink_faint) }

    var values = IntArray(0)
        set(value) {
            field = value
            invalidate()
        }
    var selected = -1
        set(value) {
            field = value
            invalidate()
        }
    /** Columns from here on are in the future. */
    var futureFrom = Int.MAX_VALUE
        set(value) {
            field = value
            invalidate()
        }
    var onPick: ((Int) -> Unit)? = null

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0) return
        val padX = paddingLeft.toFloat()
        val pitch = (width - 2 * padX) / n
        val size = (pitch * 0.72f).coerceAtMost(height / 8f)
        val base = height - paddingBottom - size
        val rows = ((base - paddingTop) / (size * 1.25f)).toInt().coerceAtLeast(1)
        val top = values.maxOrNull()?.coerceAtLeast(1) ?: 1
        for (i in 0 until n) {
            val x = padX + i * pitch
            val paint = when {
                i == selected -> accent
                i >= futureFrom -> faint
                else -> ink
            }
            canvas.drawRect(x, base, x + size, base + size, paint)
            val stack = if (values[i] > 0) (values[i].toFloat() / top * rows).toInt().coerceAtLeast(1) else 0
            for (b in 1..stack) {
                val y = base - b * size * 1.25f
                canvas.drawRect(x, y, x + size, y + size, paint)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (values.isEmpty()) return false
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val pitch = (width - 2f * paddingLeft) / values.size
            val i = ((event.x - paddingLeft) / pitch).toInt().coerceIn(0, values.size - 1)
            onPick?.invoke(i)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
