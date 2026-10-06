package cash.nonfungible.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cash.nonfungible.app.entry.Admitted
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Tickets
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CollectionsTest {
    private val FIRST = Account(Account.FIRST, "Ada")

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val evening = page("ab")
    private val matinee = page("cd")
    private val h = "71".repeat(32)

    private fun page(key: String) =
        checkNotNull(Collection.parse("https://mint.example/p/${key.repeat(32)}"))

    @Test
    fun `a new phone keeps no collection`() {
        assertEquals(emptyList<Saved>(), Collections(context, FIRST).all)
        assertNull(Collections(context, FIRST).selected)
    }

    @Test
    fun `the collection added last is the one on the door`() {
        Collections(context, FIRST).add(evening, "An Evening")
        Collections(context, FIRST).add(matinee, "A Matinee")
        val kept = Collections(context, FIRST)
        assertEquals(listOf("An Evening", "A Matinee"), kept.all.map(Saved::name))
        assertEquals(matinee, kept.selected?.collection)
    }

    @Test
    fun `the operator puts another collection on the door`() {
        val kept = Collections(context, FIRST)
        kept.add(evening, "An Evening")
        kept.add(matinee, "A Matinee")
        kept.select(evening)
        assertEquals(evening, Collections(context, FIRST).selected?.collection)
    }

    @Test
    fun `adding a collection again renames it and keeps its record`() {
        val kept = Collections(context, FIRST)
        kept.add(evening, "An Evening")
        Admitted(context, evening).record(h, "Door")
        kept.add(evening, "An Evening, extended")
        assertEquals(listOf("An Evening, extended"), kept.all.map(Saved::name))
        assertEquals(1, Admitted(context, evening).count)
    }

    @Test
    fun `removing a collection forgets its tickets and who came in, and no other's`() {
        val kept = Collections(context, FIRST)
        kept.add(evening, "An Evening")
        kept.add(matinee, "A Matinee")
        val cards = JsonParser.parseString("""[{"h":"$h","title":"1"}]""").asJsonArray
        for (one in listOf(evening, matinee)) {
            Tickets(context, one).save(cards)
            Admitted(context, one).record(h, "Door")
        }
        (context as App).forget(FIRST, matinee)
        assertEquals(evening, kept.selected?.collection)
        assertEquals(0, Admitted(context, matinee).count)
        assertEquals(emptyList<Any>(), Tickets(context, matinee).all)
        assertEquals(1, Admitted(context, evening).count)
        assertEquals(1, Tickets(context, evening).all.size)
    }

    @Test
    fun `what an earlier build kept for a collection is still there`() {
        Collections(context, FIRST).add(evening, "An Evening")
        // The name an earlier build kept a collection's record under.
        val old = "admitted-" + evening.url.replace(Regex("[^a-z0-9]+"), "_")
        context.getSharedPreferences(old, Context.MODE_PRIVATE).edit()
            .putString(h, "1791213000 main entrance").commit()

        Collections(context, FIRST)

        val earlier = checkNotNull(Admitted(context, evening).all[h])
        assertEquals("main entrance", earlier.door)
        assertEquals(1791213000, earlier.at)
        assertTrue(context.getSharedPreferences(old, Context.MODE_PRIVATE).all.isEmpty())
    }

    @Test
    fun `a collection is its maker's, and another account that keeps its door neither owns it nor takes its records with it`() {
        val app = context as App
        val maker = app.accounts.add("Ada")
        val keeper = app.accounts.add("Door crew")
        app.collectionsOf(maker).add(evening, "An Evening", mine = true)
        app.collectionsOf(keeper).add(evening, "An Evening")
        Admitted(context, evening).record(h, "Door")
        // Whose it is does not change by being kept again, or by who is looking.
        app.collectionsOf(maker).add(evening, "An Evening, renamed")
        assertTrue(app.collectionsOf(maker).all.single().mine)
        assertFalse(app.collectionsOf(keeper).all.single().mine)
        app.accounts.use(keeper)
        assertFalse(app.owns(evening))
        app.accounts.use(maker)
        assertTrue(app.owns(evening))
        // The keeper lets it go: the maker still has it, and who came in is still known.
        app.forget(keeper, evening)
        assertTrue(app.collectionsOf(keeper).all.isEmpty())
        assertEquals(1, Admitted(context, evening).count)
        // The maker lets it go too: now nobody keeps it, and its records go.
        app.forget(maker, evening)
        assertEquals(0, Admitted(context, evening).count)
    }
}
