package cash.nonfungible.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * A collection's picture when it has given itself none: the mark the site draws for it, from
 * its key, so that a collection looks the same here as on its page. Five cells by five on a
 * square of seven, mirrored down the middle; the key's first byte picks the colour and its
 * next fifteen pick the cells, as the site's `Identicon` does.
 */
class Identicon(pubkey: String, private val ground: Int) : Drawable() {

    private val bytes = pubkey.chunked(2).map { it.toInt(16) }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cell = RectF()
    private val colour = Color.parseColor(COLOURS[bytes[0] % COLOURS.size])
    private val cells = buildList {
        for (y in 0 until 5) for (x in 0 until 3) {
            if (bytes[1 + y * 3 + x] % 2 == 1) {
                add(x to y)
                if (x < 2) add(4 - x to y)
            }
        }
    }

    override fun draw(canvas: Canvas) {
        val side = bounds.width().coerceAtMost(bounds.height()) / 7f
        val left = bounds.left + (bounds.width() - side * 7) / 2 + side
        val top = bounds.top + (bounds.height() - side * 7) / 2 + side
        paint.color = ground
        canvas.drawRect(bounds, paint)
        paint.color = colour
        for ((x, y) in cells) {
            // A touch over a cell, as the site draws it, so that neighbours meet with no seam.
            cell.set(left + x * side, top + y * side, left + (x + 1.02f) * side, top + (y + 1.02f) * side)
            canvas.drawRect(cell, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    override fun setColorFilter(filter: ColorFilter?) {
        paint.colorFilter = filter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.OPAQUE

    private companion object {
        val COLOURS = listOf("#1f6feb", "#0f8a5f", "#d9480f", "#c92a2a", "#0b7285", "#b08800", "#171717")
    }
}
