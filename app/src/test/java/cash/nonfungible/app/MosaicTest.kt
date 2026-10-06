package cash.nonfungible.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View.MeasureSpec
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

/** Where each NFT of a batch has its place, and how much of the grid is in view as they are made. */
class MosaicTest {

    @Test
    fun `places fill in rings, so the first four, nine and sixteen are squares`() {
        assertEquals(listOf(0 to 0, 1 to 0, 0 to 1, 1 to 1), (0 until 4).map(Mosaic::place))
        assertEquals(listOf(2 to 0, 2 to 1, 0 to 2, 1 to 2, 2 to 2), (4 until 9).map(Mosaic::place))
        for (side in 1..15) {
            val square = (0 until side * side).map(Mosaic::place).toSet()
            assertEquals((0 until side).flatMap { x -> (0 until side).map { y -> x to y } }.toSet(), square)
        }
        // Every NFT has a place of its own.
        assertEquals(200, (0 until 200).map(Mosaic::place).toSet().size)
    }

    @Test
    fun `what holds the first of them is as nearly square as their number allows`() {
        assertEquals(1 to 1, Mosaic.box(1))
        assertEquals(2 to 1, Mosaic.box(2))
        assertEquals(2 to 2, Mosaic.box(3))
        assertEquals(2 to 2, Mosaic.box(4))
        assertEquals(3 to 2, Mosaic.box(6))
        assertEquals(3 to 3, Mosaic.box(8))
        assertEquals(4 to 4, Mosaic.box(16))
        assertEquals(6 to 6, Mosaic.box(32))
        assertEquals(10 to 10, Mosaic.box(100))
        // Nothing made has a place outside what holds them all.
        for (count in 1..200) {
            val (wide, tall) = Mosaic.box(count)
            for (index in 0 until count) {
                val (x, y) = Mosaic.place(index)
                assertTrue("$index of $count", x < wide && y < tall)
            }
        }
    }

    @Test
    fun `the view draws back when one, two, four, eight are made`() {
        assertEquals(listOf(1, 2, 4, 4, 8, 8, 8, 8, 16), (0..8).map { Mosaic.inView(it, 100) })
        // Never to more than there are.
        assertEquals(listOf(1, 2, 4, 4, 8, 8, 8, 8, 10, 10, 10), (0..10).map { Mosaic.inView(it, 10) })
        assertEquals(1, Mosaic.inView(0, 1))
        assertEquals(1, Mosaic.inView(1, 1))
    }
}

/** What the grid draws, on a real canvas: only the places the view has drawn back to. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MosaicDrawingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val mosaic = Mosaic(context).apply {
        measure(
            MeasureSpec.makeMeasureSpec(300, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(600, MeasureSpec.EXACTLY),
        )
        layout(0, 0, 300, 600)
    }

    private fun drawn(): Bitmap =
        Bitmap.createBitmap(300, 600, Bitmap.Config.ARGB_8888)
            .also { mosaic.draw(Canvas(it)) }

    @Test
    fun `at first only the first place is in view, and it is a field of dots`() {
        mosaic.begin(6)
        val seen = drawn()
        // The first place fills the width, in the middle of the view.
        assertTrue(Color.alpha(seen.getPixel(150, 300)) > 0)
        // The place under it exists and is not shown yet, though the view has room for it.
        assertEquals(0, Color.alpha(seen.getPixel(150, 560)))
        assertEquals(0, Color.alpha(seen.getPixel(150, 60)))
    }

    @Test
    fun `when the first is made the view draws back to two, and no further`() {
        mosaic.begin(6)
        val red = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
            .apply { eraseColor(Color.RED) }
        mosaic.made(red)
        ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS)
        val seen = drawn()
        // Two places side by side, each half the width: the picture, and dots for the next.
        assertEquals(Color.RED, seen.getPixel(75, 300))
        assertTrue(Color.alpha(seen.getPixel(225, 300)) > 0)
        assertNotEquals(Color.RED, seen.getPixel(225, 300))
        // The row under them is not in view yet.
        assertEquals(0, Color.alpha(seen.getPixel(75, 450)))
        assertEquals(0, Color.alpha(seen.getPixel(225, 450)))
    }
}
