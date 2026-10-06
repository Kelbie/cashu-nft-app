package cash.nonfungible.app.entry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A door's codes change every period and are honoured for one more. */
@RunWith(RobolectricTestRunner::class)
class RotationTest {

    private var made = 0
    private val codes = Rotation(periodMs = 30_000) {
        Entry.request(Vectors.collection, Vectors.door, Vectors.nprofile, "%032x".format(++made))
    }

    private fun answerTo(asked: Asked) =
        Answer(asked.id, listOf(Showing(Entry.context(asked.encoded), ByteArray(321))))

    @Test
    fun `a code stays on screen for its period, then another takes its place`() {
        val first = codes.current(0)
        assertSame(first, codes.current(29_999))
        assertEquals(1, codes.left(29_999))
        val second = codes.current(30_000)
        assertNotEquals(first.encoded, second.encoded)
        assertEquals(30_000, codes.left(30_000))
        // Only the request's id changes, so the door listens in one place throughout.
        assertEquals(first.collection, second.collection)
    }

    @Test
    fun `an answer to the code on screen is taken`() {
        val shown = codes.current(0)
        assertSame(shown, (codes.take(answerTo(shown), 10_000) as Taken.Live).asked)
    }

    @Test
    fun `an answer to the code just replaced is still taken`() {
        val first = codes.current(0)
        codes.current(30_000)
        assertSame(first, (codes.take(answerTo(first), 59_999) as Taken.Live).asked)
    }

    @Test
    fun `an answer to a code two periods old is late`() {
        val first = codes.current(0)
        codes.current(30_000)
        codes.current(60_000)
        assertEquals(Taken.Late, codes.take(answerTo(first), 60_000))
    }

    @Test
    fun `an answer brings on a new code, though the old one had time left`() {
        val shown = codes.current(0)
        assertTrue(codes.take(answerTo(shown), 5_000) is Taken.Live)
        assertNotEquals(shown.encoded, codes.current(5_100).encoded)
    }

    @Test
    fun `an answer that is yet to be borne out is matched and spends nothing`() {
        val shown = codes.current(0)
        // Anybody can send what looks like an answer. Looking at it costs the code nothing.
        assertSame(shown, (codes.match(answerTo(shown), 1_000) as Taken.Live).asked)
        assertSame(shown, codes.current(1_100))
        assertSame(shown, codes.shown(shown.id))
        assertTrue(codes.take(answerTo(shown), 2_000) is Taken.Live)
        assertEquals(Taken.None, codes.match(answerTo(shown), 2_100))
        assertEquals(null, codes.shown(shown.id))
    }

    @Test
    fun `a code is answered once`() {
        val shown = codes.current(0)
        assertTrue(codes.take(answerTo(shown), 1_000) is Taken.Live)
        assertEquals(Taken.None, codes.take(answerTo(shown), 2_000))
    }

    @Test
    fun `an answer to nothing the door showed is not taken`() {
        val shown = codes.current(0)
        val other = Entry.request(Vectors.collection, Vectors.door, Vectors.nprofile, "ff".repeat(16))
        assertEquals(Taken.None, codes.take(answerTo(other), 1_000))
        // Nor is the right id over the wrong text.
        val wrong = Answer(shown.id, listOf(Showing(Entry.context(other.encoded), ByteArray(321))))
        assertEquals(Taken.None, codes.take(wrong, 1_000))
    }

    @Test
    fun `a code long gone is forgotten`() {
        val first = codes.current(0)
        codes.current(300_000)
        assertEquals(Taken.None, codes.take(answerTo(first), 300_000))
    }
}
