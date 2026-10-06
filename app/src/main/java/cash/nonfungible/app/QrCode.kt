package cash.nonfungible.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.google.zxing.qrcode.encoder.Encoder

/**
 * A QR code as large as its bounds allow, with every module a whole number of pixels and the
 * full quiet zone around it. A request makes a dense code, and a camera reads a dense code only
 * when its edges are sharp.
 */
class QrCode(text: String) : Drawable() {
    private val modules = Encoder.encode(text, ErrorCorrectionLevel.L).matrix
    private val paint = Paint()

    override fun draw(canvas: Canvas) {
        val count = modules.width
        val pitch = minOf(bounds.width(), bounds.height()) / (count + 2 * QUIET_ZONE)
        val side = pitch * (count + 2 * QUIET_ZONE)
        val left = bounds.left + (bounds.width() - side) / 2f
        val top = bounds.top + (bounds.height() - side) / 2f
        paint.color = Color.WHITE
        canvas.drawRect(left, top, left + side, top + side, paint)
        paint.color = Color.BLACK
        for (y in 0 until count) {
            for (x in 0 until count) {
                if (modules[x, y].toInt() != 1) continue
                val x0 = left + (x + QUIET_ZONE) * pitch
                val y0 = top + (y + QUIET_ZONE) * pitch
                canvas.drawRect(x0, y0, x0 + pitch, y0 + pitch, paint)
            }
        }
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private companion object {
        const val QUIET_ZONE = 4
    }
}
