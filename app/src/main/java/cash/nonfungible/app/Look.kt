package cash.nonfungible.app

import android.graphics.Bitmap
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette

/**
 * The colours a screen takes from an NFT's picture, as a record's sleeve colours its player:
 * the ground the picture sits on, the ink that reads on it, and the paper thrown for it.
 */
class Look(val ground: Int, val ink: Int, val accent: Int, val paper: List<Int>) {
    /** A shade of the ground for something to stand on it: the ground, a little toward the ink. */
    val panel: Int get() = ColorUtils.blendARGB(ground, ink, 0.09f)

    /** The ink, quieter: for a label beside what it labels. */
    val quiet: Int get() = ColorUtils.setAlphaComponent(ink, 165)

    /** The ink as a hairline between one thing and the next. */
    val rule: Int get() = ColorUtils.setAlphaComponent(ink, 60)

    companion object {
        private const val BLACK = 0xFF111111.toInt()
        private const val CREAM = 0xFFF4F0E6.toInt()

        /** Whichever of the site's two inks reads better on a colour. */
        fun inkOn(ground: Int): Int = listOf(BLACK, CREAM)
            .maxBy { ColorUtils.calculateContrast(it, ground or 0xFF000000.toInt()) }

        /** Whether dark things read on a colour, such as the icons of a bar drawn over it. */
        fun isLight(ground: Int): Boolean = inkOn(ground) == BLACK

        private fun saturation(colour: Int): Float = FloatArray(3).also { ColorUtils.colorToHSL(colour, it) }[1]

        /** Whether a colour is paper: light, and with little colour in it. */
        private fun isPale(colour: Int): Boolean {
            val hsl = FloatArray(3).also { ColorUtils.colorToHSL(colour, it) }
            return hsl[2] > 0.82f && (hsl[1] < 0.3f || hsl[2] > 0.95f)
        }

        /** The look of a page that takes no colours from a picture: the app's own. */
        fun plain(ground: Int, ink: Int, accent: Int): Look = Look(ground, ink, accent, emptyList())

        /** Reads a picture's colours. Not for the main thread. */
        fun of(picture: Bitmap): Look {
            // Black and white count: a picture on black belongs on a black page.
            val palette = Palette.from(picture).clearFilters().maximumColorCount(16).generate()
            // The colour there is most of carries the picture's own ground out to the edges. A
            // picture that is mostly paper would leave the page paper too, and no page its own:
            // its liveliest colour that there is a fair amount of takes the page instead.
            val most = palette.getDominantColor(CREAM)
            val all = palette.swatches.sumOf { it.population }
            val ground = if (!isPale(most)) most else palette.swatches
                .filter { saturation(it.rgb) > 0.35f && !isPale(it.rgb) && it.population * 25 > all }
                .maxByOrNull { it.population * saturation(it.rgb) }?.rgb ?: most
            val lively = listOfNotNull(
                palette.vibrantSwatch, palette.lightVibrantSwatch, palette.darkVibrantSwatch,
                palette.mutedSwatch, palette.lightMutedSwatch,
            ).map { it.rgb }.filter { ColorUtils.calculateContrast(it, ground) > 1.25 }
            val accent = lively.firstOrNull() ?: inkOn(ground)
            return Look(ground, inkOn(ground), accent, (lively + Color.WHITE + accent).distinct())
        }
    }
}
