package cash.nonfungible.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Paper thrown up from a point, falling away: flat shapes in the colours it is given. */
class Confetti @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    private class Piece(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val size: Float, val color: Int, var turn: Float, val spin: Float, val round: Boolean,
    )

    private val pieces = ArrayList<Piece>()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var last = 0L

    /** Throws paper from a point. Nothing moves on a phone set to show no animations. */
    fun burst(x: Float, y: Float, colors: List<Int>) {
        if (!ValueAnimator.areAnimatorsEnabled() || colors.isEmpty()) return
        repeat(PIECES) {
            // Mostly upward, fanned to either side.
            val angle = -PI / 2 + (Random.nextDouble() - 0.5) * PI * 0.9
            val speed = density * (420 + Random.nextFloat() * 760)
            pieces += Piece(
                x, y, (cos(angle) * speed).toFloat(), (sin(angle) * speed).toFloat(),
                density * (7 + Random.nextFloat() * 8), colors.random(),
                Random.nextFloat() * 360, (Random.nextFloat() - 0.5f) * 900, Random.nextInt(4) == 0,
            )
        }
        last = 0
        postInvalidateOnAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        if (pieces.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val step = if (last == 0L) 0f else ((now - last) / 1000f).coerceAtMost(0.05f)
        last = now
        val each = pieces.iterator()
        while (each.hasNext()) {
            val piece = each.next()
            piece.vy += GRAVITY * density * step
            piece.vx *= 1 - 1.4f * step
            piece.x += piece.vx * step
            piece.y += piece.vy * step
            piece.turn += piece.spin * step
            if (piece.y > height + piece.size) {
                each.remove()
                continue
            }
            paint.color = piece.color
            if (piece.round) {
                canvas.drawCircle(piece.x, piece.y, piece.size / 2.4f, paint)
            } else {
                // Paper turns over as it falls: its face narrows and widens.
                val face = piece.size / 2 * abs(cos(Math.toRadians(piece.turn * 0.6))).toFloat()
                canvas.save()
                canvas.rotate(piece.turn, piece.x, piece.y)
                canvas.drawRect(
                    piece.x - piece.size / 2, piece.y - face,
                    piece.x + piece.size / 2, piece.y + face, paint
                )
                canvas.restore()
            }
        }
        if (pieces.isNotEmpty()) postInvalidateOnAnimation()
    }

    private companion object {
        const val PIECES = 110
        const val GRAVITY = 1500f
    }
}
