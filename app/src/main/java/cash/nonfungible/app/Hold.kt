package cash.nonfungible.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import androidx.core.content.res.ResourcesCompat

/**
 * A button that must be held: for what cannot be undone, and for nothing else. A fill runs
 * across it while a finger is down, and what it is for is done when the fill gets to the end.
 * Let go sooner and it runs back to nothing.
 *
 * Its words are drawn twice and cut at the fill's edge: in the fill's colour on the paper ahead
 * of it, in white on the fill behind it. So they can be read at every moment of the hold.
 */
class HoldButton @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {

    /** What it says: "Hold to delete". */
    var text: CharSequence = ""
        set(said) {
            field = said
            contentDescription = said
            invalidate()
        }

    /** What is done once it has been held to the end. */
    var onHeld: (() -> Unit)? = null

    /** How long it is held. Long enough to mean it. */
    var holdMs = 1600L

    private val density = resources.displayMetrics.density
    private val edge = 2 * density
    private val danger = context.getColor(R.color.bad)
    private val faint = context.getColor(R.color.faint)
    private val quiet = context.getColor(R.color.quiet)
    private val ground = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = context.getColor(R.color.paper) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = danger
        style = Paint.Style.STROKE
        strokeWidth = edge
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = danger }
    private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = ResourcesCompat.getFont(context, R.font.inter_700)
        textSize = 16 * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val pill = Path()
    private val bounds = RectF()

    /** When the finger came down, by the clock that does not stop; none while it is up. */
    private var since: Long? = null

    /** How far the fill had got when the finger went up, and when that was. */
    private var left = 0f
    private var leftAt = 0L
    private var done = false

    /** The finger stayed down to the end. One that has since gone, or a button since switched off, held nothing. */
    private val held = Runnable { if (isEnabled && since != null) finish() }

    private fun finish() {
        done = true
        since = null
        left = 1f
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
        onHeld?.invoke()
    }

    init {
        isClickable = true
        isFocusable = true
        minimumHeight = (56 * density).toInt()
    }

    override fun setEnabled(enabled: Boolean) {
        super.setEnabled(enabled)
        if (!enabled) letGo()
        invalidate()
    }

    /** Back as it was, to be held again. */
    fun reset() {
        removeCallbacks(held)
        done = false
        since = null
        left = 0f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(minimumHeight, heightMeasureSpec),
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        bounds.set(edge / 2, edge / 2, w - edge / 2, h - edge / 2)
        pill.rewind()
        pill.addRoundRect(bounds, h / 2f, h / 2f, Path.Direction.CW)
    }

    /**
     * How far the fill has got. The hold is timed by the clock and the fill only shows it, so a
     * phone that runs its animations fast, or not at all, is still held for the whole of it.
     */
    private fun progress(now: Long): Float {
        if (done) return 1f
        since?.let { return ((now - it).toFloat() / holdMs).coerceIn(0f, 1f) }
        return (left - (now - leftAt).toFloat() / RETURN_MS).coerceIn(0f, 1f)
    }

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val at = progress(now)
        val split = bounds.left + bounds.width() * at
        val middle = bounds.centerY() - (words.descent() + words.ascent()) / 2
        canvas.save()
        canvas.clipPath(pill)
        canvas.drawRect(bounds, ground)
        canvas.drawRect(bounds.left, bounds.top, split, bounds.bottom, fill)
        // Ahead of the fill, the words in its colour. One that cannot be held yet says so as any button does.
        canvas.save()
        canvas.clipRect(split, bounds.top, bounds.right, bounds.bottom)
        words.color = if (isEnabled) danger else quiet
        canvas.drawText(text, 0, text.length, bounds.centerX(), middle, words)
        canvas.restore()
        // Behind it, the same words in white.
        canvas.save()
        canvas.clipRect(bounds.left, bounds.top, split, bounds.bottom)
        words.color = WHITE
        canvas.drawText(text, 0, text.length, bounds.centerX(), middle, words)
        canvas.restore()
        canvas.restore()
        line.color = if (isEnabled) danger else faint
        canvas.drawPath(pill, line)
        if (!done && (since != null || at > 0f)) postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || done) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // A finger held still is never quite still. A sheet would take the drift for a
                // drag and the hold would end: the hold is this button's alone.
                parent?.requestDisallowInterceptTouchEvent(true)
                since = SystemClock.uptimeMillis()
                postDelayed(held, holdMs)
                invalidate()
            }
            // A second finger is not a firmer hold: whatever else touches the screen ends it.
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> letGo()
            MotionEvent.ACTION_MOVE -> if (event.x < 0 || event.x > width || event.y < -height || event.y > 2 * height) letGo()
        }
        return true
    }

    private fun letGo() {
        val began = since ?: return
        val now = SystemClock.uptimeMillis()
        removeCallbacks(held)
        left = ((now - began).toFloat() / holdMs).coerceIn(0f, 1f)
        leftAt = now
        since = null
        invalidate()
    }

    /**
     * Somebody who cannot hold a finger down, and uses the phone by what it reads out, has
     * already had to find this button and ask for it twice over. That is as deliberate.
     */
    override fun performClick(): Boolean {
        super.performClick()
        if (!isEnabled || done) return false
        sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_CLICKED)
        finish()
        return true
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(held)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()

        /** How long the fill takes to run back from full. */
        const val RETURN_MS = 220L
    }
}
