package cash.nonfungible.app.entry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TicketsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val collection = Vectors.collection
    private val first = "71".repeat(32)
    private val second = "72".repeat(32)

    private fun card(id: String, h: String, status: String) = JsonObject().apply {
        addProperty("id", id)
        addProperty("h", h)
        addProperty("title", "Ticket $id")
        addProperty("created", 1_700_000_000)
        addProperty("status", status)
    }

    @Test
    fun `an NFT that was passed on and came back is held`() {
        // A page lists it twice: the card that was sold, and the card that was bought back.
        val cards = JsonArray().apply {
            add(card("old", first, "sent"))
            add(card("other", second, "sent"))
            add(card("new", first, "owned"))
        }
        val tickets = Tickets(context, collection)
        tickets.save(cards)
        assertEquals(listOf(first, second), tickets.all.map { it.h })
        val back = tickets.all.first { it.h == first }
        assertFalse(back.sent)
        assertEquals("new", back.id)
        assertTrue(tickets.all.first { it.h == second }.sent)
    }

    @Test
    fun `an NFT just given to the phone is kept without the page being read`() {
        val tickets = Tickets(context, collection)
        tickets.save(JsonArray().apply { add(card("a", first, "sent")) })
        tickets.add(Ticket(second, "A gift", 1_700_000_100, id = "b"))
        // One it had passed on and is given again is held again, once.
        tickets.add(Ticket(first, "Back again", 1_700_000_200, id = "c"))
        val kept = Tickets(context, collection).all
        assertEquals(setOf(first, second), kept.map { it.h }.toSet())
        assertEquals(2, kept.size)
        assertTrue(kept.none { it.sent })
        assertEquals("c", kept.first { it.h == first }.id)
    }
}
