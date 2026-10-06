package cash.nonfungible.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Picture
import android.graphics.RectF
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.core.view.ScrollingView
import androidx.core.view.children
import androidx.core.view.isVisible
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Broad blobs that drift slowly through a square and run into one another where they meet. A
 * dot on a grid is as large as the blobs passing over it are near: that is how the app says
 * that something is on its way.
 */
class Blobs {
    private val x = FloatArray(COUNT)
    private val y = FloatArray(COUNT)

    /** Puts the blobs where they are at `time`, in seconds. `out` sets one field out of step with the next. */
    fun move(time: Float, out: Float = 0f) {
        for (blob in 0 until COUNT) {
            x[blob] = 0.5f + 0.42f * sin(time * (0.21f + 0.07f * blob) + out + blob * 2.1f)
            y[blob] = 0.5f + 0.42f * cos(time * (0.27f + 0.05f * blob) + out * 1.3f + blob * 1.3f)
        }
    }

    /**
     * How much a dot at a place in the square swells: nothing far from every blob, one inside
     * one, and smooth between. A blob is about half the square across; two that overlap count as one.
     */
    fun swell(u: Float, v: Float): Float {
        var pull = 0f
        for (blob in 0 until COUNT) {
            val dx = u - x[blob]
            val dy = v - y[blob]
            pull += 0.106f / (dx * dx + dy * dy + 0.03f)
        }
        val near = ((pull - 0.5f) / 1.5f).coerceIn(0f, 1f)
        return near * near * (3 - 2 * near)
    }

    private companion object {
        const val COUNT = 3
    }
}

/**
 * A field of dots that swell and shrink as blobs drift through it: what the app shows while it
 * waits, in place of a spinner or a bar. It keeps still on a phone told to show no animations.
 */
class Dots @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    /** The colour of the dots. */
    var ink: Int = context.getColor(R.color.muted)
        set(colour) {
            field = colour
            invalidate()
        }

    /**
     * Whether words are written over the dots. They are then fainter and smaller, so that a blob
     * passing behind a word never makes it hard to read.
     */
    var behindWords: Boolean = false
        set(behind) {
            field = behind
            invalidate()
        }

    private val blobs = Blobs()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val shape = Path()
    private val bounds = RectF()

    override fun onDraw(canvas: Canvas) {
        if (width == 0 || height == 0) return
        // A honeycomb: every second row sits half a dot across, so each dot has six neighbours.
        val pitch = PITCH_DP * density
        val step = pitch * ROW
        val columns = (width / pitch).toInt().coerceAtLeast(2)
        val rows = (height / step).toInt().coerceAtLeast(1)
        // The comb sits in the middle of the view, with what is left over shared around it.
        val left = (width - (columns - 0.5f) * pitch) / 2
        val top = (height - (rows - 1) * step) / 2
        // The blobs live in a square as wide as the longer side, so they stay round.
        val across = maxOf(columns * pitch, rows * step)
        blobs.move((SystemClock.uptimeMillis() % 3_600_000) / 1000f)
        bounds.set(0f, 0f, width.toFloat(), height.toFloat())
        shape.rewind()
        shape.addRoundRect(bounds, CORNER_DP * density, CORNER_DP * density, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(shape)
        paint.color = ink
        for (row in 0 until rows) {
            for (column in 0 until columns) {
                val x = left + (column + if (row % 2 == 0) 0f else 0.5f) * pitch
                val y = top + row * step
                val swell = blobs.swell((x - left) / across, (y - top) / across)
                // Quiet, as the dots of an NFT being minted are: they say something is coming, and no more.
                paint.alpha = ((if (behindWords) FAINT else PLAIN) * (0.35f + 0.65f * swell)).toInt()
                // Inside a blob the dots all but touch, as the cells of a comb do.
                canvas.drawCircle(x, y, pitch * (0.08f + (if (behindWords) 0.16f else 0.32f) * swell), paint)
            }
        }
        canvas.restore()
        // A door shows this for hours, and the blobs are slow: a frame in two is plenty.
        if (isShown && ValueAnimator.areAnimatorsEnabled()) postInvalidateDelayed(FRAME_MS)
    }

    private companion object {
        const val PITCH_DP = 13f
        const val CORNER_DP = 14f

        /** How far one row of a honeycomb is below the last, for dots one apart. */
        const val ROW = 0.866f
        const val FRAME_MS = 33L

        // How dark a dot inside a blob is, of 255: by itself, and with words over it.
        const val PLAIN = 90
        const val FAINT = 34
    }
}

/**
 * Holds what scrolls under a heading. What scrolls goes up under whatever is above it, and is
 * lost there behind a honeycomb of dots in the page's own colour. At the top the dots are large
 * and touch, and nothing shows. Further down each dot divides: it shrinks while smaller dots bud
 * in the gaps round it, until all are of a size on a comb twice as fine; then they divide again.
 * It is one comb all the way down, every dot with six neighbours, as a watch lays out its apps.
 * So the dots never thin out into specks with room between them: they get finer, and at the foot
 * are gone. The list itself is never covered: all of this is above its edge.
 */
class Halftone @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    FrameLayout(context, attrs) {

    /** The colour of the page what scrolls is on. */
    var ground: Int = context.getColor(R.color.bg)
        set(colour) {
            field = colour
            drawn = null
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private val moved = android.view.ViewTreeObserver.OnScrollChangedListener { invalidate() }

    /** How far what scrolls goes up under what is above it. */
    private val over: Float
    private var under = false

    /** The comb as it is once it is all there, kept: it is thousands of dots, and the same every time. */
    private var drawn: Picture? = null

    init {
        setWillNotDraw(false)
        val given = context.obtainStyledAttributes(attrs, R.styleable.Halftone)
        over = given.getDimension(R.styleable.Halftone_over, OVER_DP * density)
        given.recycle()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        viewTreeObserver.addOnScrollChangedListener(moved)
        if (!under) goUnder()
    }

    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnScrollChangedListener(moved)
        super.onDetachedFromWindow()
    }

    /**
     * Reaches up under what is above: this is that much taller at the top, what scrolls starts
     * where it did, and whatever came before this in its parent is drawn over it.
     */
    private fun goUnder() {
        val scrolls = scroller(this) ?: return
        val placed = layoutParams as? MarginLayoutParams ?: return
        under = true
        placed.topMargin -= over.toInt()
        layoutParams = placed
        scrolls.setPadding(scrolls.paddingLeft, scrolls.paddingTop + over.toInt(), scrolls.paddingRight, scrolls.paddingBottom)
        scrolls.clipToPadding = false
        // Whatever else is in here, what a list says when it is empty for one, starts where
        // the list does: left at the top it would be under what this reaches up beneath.
        for (other in children) {
            var holds: View? = scrolls
            while (holds != null && holds !== other) holds = holds.parent as? View
            if (holds === other) continue
            val place = other.layoutParams as? MarginLayoutParams ?: continue
            place.topMargin += over.toInt()
            other.layoutParams = place
        }
        val around = parent as? ViewGroup ?: return
        for (before in around.children.takeWhile { it !== this }) before.translationZ = density
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        drawn = null
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // It comes in over the first little way scrolled, so a list at rest has a clean edge.
        val shown = ((scroller(this)?.let(::scrolled) ?: 0f) / (COMES_IN_DP * density)).coerceIn(0f, 1f)
        if (shown <= 0f || width == 0) return
        if (shown < 1f) return comb(canvas, shown)
        val whole = drawn ?: Picture().also {
            comb(it.beginRecording(width, (over + (BELOW_DP + LARGE_DP) * density).toInt()), 1f)
            it.endRecording()
            drawn = it
        }
        canvas.drawPicture(whole)
    }

    /** Draws the comb, each dot `shown` of its size. */
    private fun comb(canvas: Canvas, shown: Float) {
        paint.color = ground
        paint.alpha = 255
        val depth = over + BELOW_DP * density
        // The cells suit the room there is: large where the comb is deep, smaller where it is not.
        val cell = (depth / 7.4f).coerceIn(SMALL_DP * density, LARGE_DP * density)
        // The finest comb, a quarter of the cells' size. A place on it, counted along the comb's
        // own two directions, is also on the comb twice as coarse when both counts are even,
        // and on the coarsest when both are multiples of four.
        val fine = cell / 4
        val step = fine * ROW
        canvas.drawRect(0f, 0f, width.toFloat(), 2 * density * shown, paint)
        var row = 0
        var y = 0f
        while (y < depth + cell) {
            val down = (y / depth).coerceIn(0f, 1f)
            // Two dividings, one after the other. While a dot divides, it and the dots budding
            // round it cover as much between them as it did alone, so no gap opens across the comb.
            val first = eased((down - 0.10f) / 0.30f)
            val second = eased((down - 0.46f) / 0.30f)
            val bud = cell / 4 * first
            val halved = sqrt(1 - 3 * (second / 2) * (second / 2))
            // And all of it thins toward the foot, where what scrolls is plain to see.
            val thin = (0.985f - 0.9f * down.pow(1.5f)) * shown
            val coarse = sqrt(cell * cell / 4 - 3 * bud * bud) * halved * thin
            val middle = bud * halved * thin
            val finest = cell / 8 * second * thin
            var column = -(row / 2) - 1
            while ((column + row / 2f) * fine < width + cell) {
                val size = when {
                    column % 4 == 0 && row % 4 == 0 -> coarse
                    column % 2 == 0 && row % 2 == 0 -> middle
                    else -> finest
                }
                if (size > 0.25f) canvas.drawCircle((column + row / 2f) * fine, y, size, paint)
                column++
            }
            y += step
            row++
        }
    }

    /** From nothing to one, gently at both ends. */
    private fun eased(along: Float): Float = along.coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

    /** The thing inside that scrolls. */
    private fun scroller(inside: ViewGroup): ViewGroup? {
        for (child in inside.children) {
            if (child is ScrollingView || child is ScrollView) return child as ViewGroup
            (child as? ViewGroup)?.let(::scroller)?.let { return it }
        }
        return null
    }

    /** How far it has been scrolled down, in pixels: nothing when it is at its top. */
    private fun scrolled(scrolls: ViewGroup): Float =
        (scrolls as? ScrollingView)?.computeVerticalScrollOffset()?.toFloat() ?: scrolls.scrollY.toFloat()

    private companion object {
        /** How wide a cell of the comb is at its top, at most and at least. */
        const val LARGE_DP = 10f
        const val SMALL_DP = 6f
        const val ROW = 0.866f

        /** How far what scrolls goes up under a heading, unless a screen says otherwise. */
        const val OVER_DP = 18f

        /** How far below the list's edge the last of the comb reaches: never as far as the first row at rest. */
        const val BELOW_DP = 10f

        /** How far a list is scrolled before the comb is all there. */
        const val COMES_IN_DP = 24f
    }
}

/**
 * A tick drawn in dots: the dots of a grid that lie along a tick swell one after another, from
 * its short stroke to the end of its long one. It is what the app shows when something has
 * worked. On a phone told to show no animations the tick is simply there.
 */
class Tick @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    /** The colour of the tick, and of the faint dots around it. */
    var ink: Int = context.getColor(R.color.ink)
        set(colour) {
            field = colour
            invalidate()
        }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var drawn = 1f
    private var drawing: ValueAnimator? = null
    private val leave = Runnable { animate().alpha(0f).setDuration(260).withEndAction { isVisible = false }.start() }

    /**
     * Draws the tick from its start. One that is `passing` is drawn over something worth
     * seeing, and goes once it has been seen.
     */
    fun play(passing: Boolean = false) {
        drawing?.cancel()
        removeCallbacks(leave)
        alpha = 1f
        isVisible = true
        if (passing) postDelayed(leave, STAY_MS)
        if (!ValueAnimator.areAnimatorsEnabled()) {
            drawn = 1f
            return invalidate()
        }
        drawing = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 620
            addUpdateListener {
                drawn = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        drawing?.cancel()
        removeCallbacks(leave)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val side = minOf(width, height).toFloat()
        if (side <= 0f) return
        val left = (width - side) / 2
        val top = (height - side) / 2
        val pitch = side / GRID
        paint.color = ink
        for (row in 0 until GRID) {
            for (column in 0 until GRID) {
                val u = (column + 0.5f) / GRID
                val v = (row + 0.5f) / GRID
                val (along, away) = onTick(u, v)
                // A dot on the tick swells as the drawing reaches it, a little past its size and back.
                val reached = ((drawn * 1.25f - along) / 0.25f).coerceIn(0f, 1f)
                val on = if (away < 0.075f) reached else 0f
                val pop = 1f + 0.35f * sin(on * Math.PI.toFloat())
                paint.alpha = (255 * (0.14f + 0.86f * on)).toInt()
                canvas.drawCircle(
                    left + u * side, top + v * side,
                    pitch * (0.1f + 0.34f * on * pop), paint,
                )
            }
        }
    }

    /** Where a place is on the tick: how far along it, from nothing to one, and how far from it. */
    private fun onTick(u: Float, v: Float): Pair<Float, Float> {
        var best = Float.MAX_VALUE
        var along = 0f
        var before = 0f
        for (stroke in 0 until STROKE.size - 1) {
            val (ax, ay) = STROKE[stroke]
            val (bx, by) = STROKE[stroke + 1]
            val length = hypot(bx - ax, by - ay)
            val t = (((u - ax) * (bx - ax) + (v - ay) * (by - ay)) / (length * length)).coerceIn(0f, 1f)
            val away = hypot(u - (ax + t * (bx - ax)), v - (ay + t * (by - ay)))
            if (away < best) {
                best = away
                along = (before + t * length) / LENGTH
            }
            before += length
        }
        return along to best
    }

    private companion object {
        const val GRID = 11
        const val STAY_MS = 1700L

        // The corners of a tick in a square: down its short stroke, then up its long one.
        val STROKE = listOf(0.2f to 0.52f, 0.42f to 0.74f, 0.82f to 0.28f)
        val LENGTH = STROKE.zipWithNext { (ax, ay), (bx, by) -> hypot(bx - ax, by - ay) }.sum()
    }
}
