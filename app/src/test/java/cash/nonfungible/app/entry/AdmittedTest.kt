package cash.nonfungible.app.entry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
class AdmittedTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val collection = Vectors.collection
    private val h = "71".repeat(32)

    @Test
    fun `a ticket is recorded the first time and reported after that`() {
        val admitted = Admitted(context, collection)
        val before = System.currentTimeMillis() / 1000
        assertNull(admitted.record(h, "main entrance"))
        val earlier = checkNotNull(admitted.record(h, "side door"))
        assertEquals("main entrance", earlier.door)
        assertTrue(earlier.at in before..before + 60)
        assertEquals(1, admitted.count)
    }

    @Test
    fun `the record outlives the object that made it`() {
        assertNull(Admitted(context, collection).record(h, "main entrance"))
        val reopened = Admitted(context, collection)
        assertEquals(1, reopened.count)
        assertEquals("main entrance", reopened.record(h, "side door")?.door)
    }

    @Test
    fun `answers arriving together let a ticket in once`() {
        val start = CountDownLatch(1)
        val let = AtomicInteger()
        val doors = List(16) {
            thread {
                start.await()
                if (Admitted(context, collection).record(h, "door $it") == null) let.incrementAndGet()
            }
        }
        start.countDown()
        doors.forEach(Thread::join)
        assertEquals(1, let.get())
        assertEquals(1, Admitted(context, collection).count)
    }

    @Test
    fun `each ticket is recorded on its own`() {
        val admitted = Admitted(context, collection)
        assertNull(admitted.record(h, "main entrance"))
        assertNull(admitted.record("72".repeat(32), "main entrance"))
        assertEquals(2, admitted.count)
    }

    @Test
    fun `each collection has a record of its own`() {
        val other = checkNotNull(Collection.parse("https://mint.example/p/${"cd".repeat(32)}"))
        assertNull(Admitted(context, collection).record(h, "main entrance"))
        assertNull(Admitted(context, other).record(h, "main entrance"))
        Admitted(context, other).clear()
        assertEquals(1, Admitted(context, collection).count)
        assertEquals(setOf(h), Admitted(context, collection).all.keys)
    }

    @Test
    fun `clearing lets every ticket in again`() {
        val admitted = Admitted(context, collection)
        admitted.record(h, "main entrance")
        admitted.clear()
        assertEquals(0, admitted.count)
        assertEquals(0, Admitted(context, collection).count)
        assertNull(admitted.record(h, "main entrance"))
    }
}
