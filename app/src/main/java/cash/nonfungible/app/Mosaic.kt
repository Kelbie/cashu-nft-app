package cash.nonfungible.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The NFTs of a batch as they are made. The grid is decided before the first is drawn, so
 * every NFT has its place from the start. The view begins close on the first place and draws
 * back each time the number made reaches a power of two, to take in twice as many. A place
 * whose NFT is not made yet is a field of dots that swell and shrink as blobs drift through it.
 */
class Mosaic @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    /** A made NFT was touched: which, counting from the first. */
    var onPick: ((Int) -> Unit)? = null

    private var places = 0
    private val pictures = ArrayList<Bitmap?>()
    private val landed = ArrayList<Long>()

    /** How many places the view takes in, across and down. They change as it draws back. */
    private var across = 1f
    private var down = 1f
    private var drawingBack: ValueAnimator? = null

    private val density = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = context.getColor(R.color.ink)
    }
    private val ink = context.getColor(R.color.ink)
    private val dot = context.getColor(R.color.muted)

    /** What a dot opens onto as its place's picture arrives: the picture itself, drawn through it. */
    private val window = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val opening = HashMap<Int, BitmapShader>()
    private val fit = Matrix()
    private val whole = RectF()
    private val ground = context.getColor(R.color.surface)
    private val made = context.getColor(R.color.lime)
    private val place = RectF()
    private val shape = Path()

    /** Decides the grid: `count` places, none made yet, seen from close on the first. */
    fun begin(count: Int) {
        drawingBack?.cancel()
        places = count
        pictures.clear()
        landed.clear()
        opening.clear()
        across = 1f
        down = 1f
        invalidate()
    }

    /** The next NFT is made and takes its place: with its picture, or without if there is none. */
    fun made(picture: Bitmap?) {
        if (pictures.size >= places) return
        pictures += picture
        landed += SystemClock.uptimeMillis()
        val (wide, tall) = box(inView(pictures.size, places))
        if (wide.toFloat() != across || tall.toFloat() != down) drawBack(wide.toFloat(), tall.toFloat())
        invalidate()
    }

    private fun drawBack(wide: Float, tall: Float) {
        drawingBack?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) {
            across = wide
            down = tall
            return
        }
        val fromAcross = across
        val fromDown = down
        drawingBack = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener {
                val along = it.animatedValue as Float
                across = fromAcross + (wide - fromAcross) * along
                down = fromDown + (tall - fromDown) * along
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (places == 0) return
        val side = min(width / across, height / down)
        val left = (width - across * side) / 2
        val top = (height - down * side) / 2
        val now = SystemClock.uptimeMillis()
        val time = (now % 3_600_000) / 1000f
        var moving = false
        edge.strokeWidth = (side * 0.012f).coerceIn(density, 2.5f * density)
        for (index in 0 until places) {
            val (x, y) = place(index)
            val gap = side * 0.045f
            place.set(left + x * side + gap, top + y * side + gap, left + (x + 1) * side - gap, top + (y + 1) * side - gap)
            // Only what the view has drawn back far enough to take in is shown; a place it is
            // just reaching comes up out of nothing as it arrives.
            val reached = min(across - x, down - y).coerceIn(0f, 1f)
            if (reached <= 0f) continue
            val veil = if (reached < 1f) canvas.saveLayerAlpha(place, (reached * 255).toInt()) else -1
            val corner = side * 0.11f
            // Nothing of a dot or a picture shows past the place's rounded edge.
            shape.rewind()
            shape.addRoundRect(place, corner, corner, Path.Direction.CW)
            canvas.save()
            canvas.clipPath(shape)
            val isMade = index < pictures.size
            // On a phone told to do without animations a picture is simply there.
            val arrived = when {
                !isMade -> 0f
                !ValueAnimator.areAnimatorsEnabled() -> 1f
                else -> ((now - landed[index]) / ARRIVE_MS).coerceIn(0f, 1f)
            }
            if (arrived < 1f) {
                // Still dots, or dots opening into the picture.
                moving = true
                canvas.drawColor(ground)
                dots(canvas, index, time, index <= pictures.size, arrived)
            } else {
                opening.remove(index)
                paint.alpha = 255
                val picture = pictures[index]
                if (picture != null) canvas.drawBitmap(picture, null, place, paint) else canvas.drawColor(made)
            }
            canvas.restore()
            if (isMade) {
                // Its edge is drawn in as it arrives.
                edge.alpha = (arrived * 255).toInt()
                canvas.drawRoundRect(place, corner, corner, edge)
            }
            if (veil >= 0) canvas.restoreToCount(veil)
        }
        if (moving && ValueAnimator.areAnimatorsEnabled()) postInvalidateOnAnimation()
    }

    /**
     * A place still to be made: dots in a honeycomb, each as large as the blobs passing over it
     * are near. Three broad blobs drift slowly through every place, out of step with those of the
     * next, and run into one another where they meet. The dots stay small and grey: they say
     * that something is coming, quietly. The place being made now is a little darker than the
     * ones that wait.
     *
     * When its NFT is made, `opened` runs from nothing to one and the dots open into the
     * picture: each becomes a window onto it that widens until the windows meet, while the
     * grey fades away.
     */
    private fun dots(canvas: Canvas, index: Int, time: Float, now: Boolean, opened: Float) {
        // A honeycomb: every second row sits half a dot across, so each dot has six neighbours.
        val each = (place.width() / (DOT_DP * density)).toInt().coerceIn(2, 28)
        val pitch = place.width() / each
        val step = pitch * ROW
        val rows = (place.height() / step).toInt() + 1
        blobs.move(time, index * 1.7f)
        val eased = opened * opened * (3 - 2 * opened)
        val picture = pictures.getOrNull(index)
        if (opened > 0f) {
            window.shader = picture?.let { shown ->
                opening.getOrPut(index) { BitmapShader(shown, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }.also {
                    whole.set(0f, 0f, shown.width.toFloat(), shown.height.toFloat())
                    fit.setRectToRect(whole, place, Matrix.ScaleToFit.FILL)
                    it.setLocalMatrix(fit)
                }
            }
            window.color = if (picture == null) made else ink
            // The picture shows through at once, faintly, and is whole well before the end.
            window.alpha = (eased * 3 * 255).toInt().coerceAtMost(255)
        }
        paint.color = dot
        for (row in 0..rows) {
            for (column in 0..each) {
                val cx = place.left + (column + if (row % 2 == 0) 0.25f else 0.75f) * pitch
                val cy = place.top + (row + 0.5f) * step
                // Smoothly from a speck, far from every blob, to a dot that all but touches
                // its neighbours inside one, as the cells of a comb do.
                val swell = blobs.swell((cx - place.left) / place.width(), (cy - place.top) / place.height())
                val size = pitch * (0.07f + 0.3f * swell)
                paint.alpha = ((if (now) 150 else 75) * (0.35f + 0.65f * swell) * (1 - eased)).toInt()
                canvas.drawCircle(cx, cy, size, paint)
                // Wide enough, at the end, for neighbouring windows to cover the gaps between them.
                if (opened > 0f) canvas.drawCircle(cx, cy, size + (pitch * 0.75f - size) * eased, window)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) return true
        if (event.action != MotionEvent.ACTION_UP || places == 0) return super.onTouchEvent(event)
        val side = min(width / across, height / down)
        val x = floor((event.x - (width - across * side) / 2) / side).toInt()
        val y = floor((event.y - (height - down * side) / 2) / side).toInt()
        val touched = (0 until pictures.size).firstOrNull { place(it) == x to y }
        if (touched != null) {
            performClick()
            onPick?.invoke(touched)
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private val blobs = Blobs()

    companion object {
        private const val DOT_DP = 13f

        // How far one row of a honeycomb is below the last, for dots one apart.
        private const val ROW = 0.866f
        private const val ARRIVE_MS = 1100f

        /**
         * Where the NFT numbered `index`, from nothing, has its place: across, then down. Places
         * are filled in rings around the first corner, so the first four are a square, and the
         * first nine, and the first sixteen: however far the view has drawn back, what it shows
         * is as nearly square as the number allows.
         */
        fun place(index: Int): Pair<Int, Int> {
            val ring = floor(sqrt(index.toDouble())).toInt()
            val along = index - ring * ring
            return if (along < ring) ring to along else (along - ring) to ring
        }

        /** How many places across and down hold the first `count` NFTs. */
        fun box(count: Int): Pair<Int, Int> {
            val (x, y) = place((count - 1).coerceAtLeast(0))
            // The last place is in the ring's upright or along its foot.
            return if (x > y) (x + 1) to x else (y + 1) to (y + 1)
        }

        /**
         * How many places are in view once `done` of `count` NFTs are made: the next power of
         * two, so that the view draws back exactly when one, two, four, eight are made.
         */
        fun inView(done: Int, count: Int): Int =
            min(count, if (done == 0) 1 else Integer.highestOneBit(done) * 2)
    }
}
