package dev.pixelock.app.ui.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Bundle
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import dev.pixelock.app.R
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Ease out shared by every moving part, so the controls feel like one machine. */
private fun ease(from: Float, to: Float, ms: Long, strength: Float = 2f, onFrame: (Float) -> Unit) =
    ValueAnimator.ofFloat(from, to).apply {
        duration = ms
        interpolator = DecelerateInterpolator(strength)
        addUpdateListener { onFrame(it.animatedValue as Float) }
        start()
    }

/**
 * Analog tape. The scale slides under a fixed orange needle: it follows the finger, coasts after a
 * flick and settles on the nearest mark. Vertical when taller than wide. Screen readers can step it.
 */
class RulerView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        const val MARK_GAP_DP = 9f
        /** A flick carries the tape this far. With [COAST_EASE] it leaves at the finger's speed. */
        const val COAST_S = 0.2f
        const val COAST_EASE = 1.5f
        const val FLICK_DP_S = 250f
        const val SETTLE_MS = 220L
        const val ROLL_MS = 420L
        const val STRETCH = 0.35f
        const val STRETCH_MARKS = 5
        const val HOUR = 60
    }

    private val density = resources.displayMetrics.density
    private val markGap = MARK_GAP_DP * density
    private val minorTick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink_faint)
        strokeWidth = density * 1.5f
        strokeCap = Paint.Cap.ROUND
    }
    private val majorTick = Paint(minorTick).apply {
        color = ContextCompat.getColor(context, R.color.tile_line)
    }
    private val needle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val needleRect = RectF()

    var min = 0
    var max = 100
    var step = 5
    var onChange: ((Int) -> Unit)? = null

    /** Calls [onChange] once the tape stops, for values that are saved to disk. */
    var commitOnRelease = false

    /** Every mark the tape passes, for labels that must keep up with the finger. Nothing saves here. */
    var onScrub: ((Int) -> Unit)? = null

    private var stop = 0
    private var reported = 0
    private var position = 0f
    private var touching = false
    private var moving = false
    private var lastTouch = 0f
    private var tracker: VelocityTracker? = null
    private var motion: ValueAnimator? = null

    /** Setting this rolls the tape there. Ignored while the user has hold of it. */
    var value: Int
        get() = stop
        set(v) {
            if (moving) return
            val target = nearest(v.toFloat())
            reported = target
            if (target == stop) return
            stop = target
            stateDescription = target.toString()
            if (!isLaidOut) {
                position = target.toFloat()
                invalidate()
            } else {
                stopMotion()
                motion = ease(position, target.toFloat(), ROLL_MS) {
                    position = it
                    invalidate()
                }
            }
        }

    private val vertical get() = height > width
    private val pixelsPerUnit get() = markGap / step

    private fun nearest(v: Float): Int =
        (((v - min) / step).roundToInt() * step + min).coerceIn(min, max)

    override fun onDraw(canvas: Canvas) {
        val upright = vertical
        val span = (if (upright) height else width).toFloat()
        val across = (if (upright) width else height).toFloat()
        val center = span / 2
        val here = (position - min) / step
        val reach = ceil(center / markGap).toInt()
        // Marks run past both ends, as on a real scale. The stretch at the ends shows the limit.
        for (mark in ceil(here).toInt() - reach..floor(here).toInt() + reach) {
            val at = center + (mark - here) * markGap
            val fade = 1f - abs(at - center) / center
            if (fade <= 0f) continue
            val major = (min + mark * step) % HOUR == 0
            val paint = if (major) majorTick else minorTick
            paint.alpha = (255 * fade).toInt()
            val pad = across * if (major) 0.2f else 0.3f
            if (upright) canvas.drawLine(pad, at, across - pad, at, paint)
            else canvas.drawLine(at, pad, at, across - pad, paint)
        }
        if (upright) {
            val half = 3 * density
            needleRect.set(across * 0.14f, center - half, across * 0.86f, center + half)
            canvas.drawRoundRect(needleRect, half, half, needle)
        } else {
            val half = density
            needleRect.set(center - half, 0f, center + half, across * 0.22f)
            canvas.drawRect(needleRect, needle)
            needleRect.set(center - half, across * 0.78f, center + half, across)
            canvas.drawRect(needleRect, needle)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        val at = if (vertical) event.y else event.x
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                stopMotion()
                touching = true
                moving = true
                lastTouch = at
                tracker = VelocityTracker.obtain()
                tracker?.addMovement(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touching) return true
                tracker?.addMovement(event)
                val delta = (at - lastTouch) / pixelsPerUnit
                lastTouch = at
                val outside = position < min || position > max
                val slack = STRETCH_MARKS * step
                moveTo((position - if (outside) delta * STRETCH else delta)
                    .coerceIn((min - slack).toFloat(), (max + slack).toFloat()))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!touching) return true
                touching = false
                tracker?.addMovement(event)
                tracker?.computeCurrentVelocity(1000)
                val speed = tracker?.let { -(if (vertical) it.yVelocity else it.xVelocity) } ?: 0f
                tracker?.recycle()
                tracker = null
                val flick = event.actionMasked == MotionEvent.ACTION_UP && abs(speed) > FLICK_DP_S * density
                if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
                val target = nearest(if (flick) position + speed / pixelsPerUnit * COAST_S else position)
                val ms = if (flick) (COAST_S * COAST_EASE * 2 * 1000).toLong() else SETTLE_MS
                motion = ease(position, target.toFloat(), ms, COAST_EASE, ::moveTo).apply {
                    doOnEnd { if (motion === it) finishMotion() }
                }
            }
        }
        return true
    }

    /** Moves the tape under the user's control. Each mark that reaches the needle clicks. */
    private fun moveTo(p: Float) {
        position = p
        val near = nearest(p)
        if (near != stop) {
            stop = near
            stateDescription = near.toString()
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onScrub?.invoke(near)
            if (!commitOnRelease) report()
        }
        invalidate()
    }

    private fun finishMotion() {
        motion = null
        moving = false
        report()
    }

    private fun report() {
        if (stop == reported) return
        reported = stop
        onChange?.invoke(stop)
    }

    private fun stopMotion() {
        val running = motion
        motion = null
        running?.cancel()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopMotion()
        tracker?.recycle()
        tracker = null
        touching = false
        moving = false
        position = stop.toFloat()
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val delta = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> step
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -step
            else -> return super.performAccessibilityAction(action, arguments)
        }
        value += delta
        onChange?.invoke(value)
        return true
    }
}

/** Slide to start. A plain tap does nothing by touch; a screen reader click commits. */
class SlideView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val track = Paint().apply { color = ContextCompat.getColor(context, R.color.tile) }
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.pill)
    }
    private val arrow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.on_pill)
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink_soft)
        textSize = 13 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        letterSpacing = 0.12f
    }
    private val rect = RectF()
    private val triangle = Path()
    private var offset = 0f
    private var dragging = false

    var onCommit: (() -> Unit)? = null

    var text: String = ""
        set(value) {
            field = value.uppercase()
            contentDescription = value
            invalidate()
        }

    private val thumbWidth get() = height * 1.35f
    private val travel get() = width - thumbWidth - 2 * inset
    private val inset = 8 * density

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener { if (!dragging && isEnabled) onCommit?.invoke() }
    }

    override fun onDraw(canvas: Canvas) {
        val corner = 22 * density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, corner, corner, track)
        val center = thumbWidth + (width - thumbWidth) / 2
        canvas.drawText(text, center, height / 2f - (label.ascent() + label.descent()) / 2, label)
        if (!isEnabled) return
        rect.set(inset + offset, inset, inset + offset + thumbWidth, height - inset)
        canvas.drawRoundRect(rect, 14 * density, 14 * density, thumb)
        val cx = rect.centerX()
        val cy = rect.centerY()
        val size = 7 * density
        triangle.rewind()
        triangle.moveTo(cx - size * 0.7f, cy - size)
        triangle.lineTo(cx + size, cy)
        triangle.lineTo(cx - size * 0.7f, cy + size)
        triangle.close()
        canvas.drawPath(triangle, arrow)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = event.x < inset + thumbWidth + 24 * density
                if (dragging) parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) {
                offset = (event.x - inset - thumbWidth / 2).coerceIn(0f, travel)
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val done = dragging && offset >= travel * 0.85f
                dragging = false
                if (done) {
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onCommit?.invoke()
                }
                settle()
            }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun settle() {
        ease(offset, 0f, 220) {
            offset = it
            invalidate()
        }
    }
}

/** Rotary picker for a position inside a list, as on the stats tab. */
class DialView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private companion object {
        const val TICKS = 40
        const val SWEEP = 300f
        const val START = 120f
    }

    private val density = resources.displayMetrics.density
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.tile_line)
        strokeWidth = density
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.ink_faint)
        style = Paint.Style.STROKE
        strokeWidth = density
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val tickPoints = FloatArray(TICKS * 4)
    private var dotAt = 0f
    private var dragging = false
    private var glide: ValueAnimator? = null

    var count = 1
    var onChange: ((Int) -> Unit)? = null

    var index = 0
        set(value) {
            val clamped = value.coerceIn(0, maxOf(count - 1, 0))
            if (field == clamped) return
            field = clamped
            if (!dragging) glideToIndex()
        }

    private fun glideToIndex() {
        glide?.cancel()
        if (!isLaidOut) {
            dotAt = index.toFloat()
            invalidate()
            return
        }
        glide = ease(dotAt, index.toFloat(), 260) {
            dotAt = it
            invalidate()
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = min(w, h) / 2f
        for (i in 0 until TICKS) {
            val a = Math.toRadians((START + SWEEP * i / (TICKS - 1)).toDouble())
            tickPoints[i * 4] = w / 2f + (r * 0.86f) * cos(a).toFloat()
            tickPoints[i * 4 + 1] = h / 2f + (r * 0.86f) * sin(a).toFloat()
            tickPoints[i * 4 + 2] = w / 2f + (r * 0.95f) * cos(a).toFloat()
            tickPoints[i * 4 + 3] = h / 2f + (r * 0.95f) * sin(a).toFloat()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val r = min(width, height) / 2f
        canvas.drawLines(tickPoints, tickPaint)
        canvas.drawCircle(width / 2f, height / 2f, r * 0.74f, ring)
        val fraction = if (count > 1) dotAt / (count - 1) else 0f
        val a = Math.toRadians((START + SWEEP * fraction).toDouble())
        canvas.drawCircle(
            width / 2f + r * 0.56f * cos(a).toFloat(),
            height / 2f + r * 0.56f * sin(a).toFloat(),
            10 * density,
            dot
        )
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                glide?.cancel()
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> Unit
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                glideToIndex()
                return true
            }
            else -> return true
        }
        val deg = Math.toDegrees(atan2((event.y - height / 2f).toDouble(), (event.x - width / 2f).toDouble()))
        val sweep = ((deg + 360 - START) % 360).toFloat()
        val fraction = (sweep / SWEEP).coerceIn(0f, 1f)
        val before = index
        dotAt = fraction * (count - 1)
        index = dotAt.roundToInt()
        invalidate()
        if (index != before) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onChange?.invoke(index)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        glide?.cancel()
        dragging = false
        dotAt = index.toFloat()
    }

    override fun performClick(): Boolean = super.performClick()

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD)
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        val delta = when (action) {
            AccessibilityNodeInfo.ACTION_SCROLL_FORWARD -> 1
            AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD -> -1
            else -> return super.performAccessibilityAction(action, arguments)
        }
        index += delta
        onChange?.invoke(index)
        return true
    }
}

/** One weekday column. A switch: the block sits up for on and slides down for off. */
class PillarToggle @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val density = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.page)
    }
    private val onColor = ContextCompat.getColor(context, R.color.accent_pale)
    private val offColor = ContextCompat.getColor(context, R.color.ink_faint)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var lift = 0f
    private var slide: ValueAnimator? = null

    var checked = false
        set(value) {
            if (field == value) return
            field = value
            stateDescription = if (value) context.getString(R.string.routine_on) else context.getString(R.string.routine_off)
            val target = if (value) 1f else 0f
            slide?.cancel()
            if (!isLaidOut) {
                lift = target
                invalidate()
            } else {
                slide = ease(lift, target, 240) {
                    lift = it
                    invalidate()
                }
            }
        }

    var onToggle: ((Boolean) -> Unit)? = null

    init {
        isClickable = true
        isFocusable = true
        setOnClickListener {
            checked = !checked
            onToggle?.invoke(checked)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val corner = 10 * density
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawRoundRect(rect, corner, corner, trackPaint)
        val inset = 4 * density
        val block = height * 0.5f
        val top = inset + (1 - lift) * (height - 2 * inset - block)
        fillPaint.color = ColorUtils.blendARGB(offColor, onColor, lift)
        rect.set(inset, top, width - inset, top + block)
        canvas.drawRoundRect(rect, corner, corner, fillPaint)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        slide?.cancel()
        lift = if (checked) 1f else 0f
    }
}
