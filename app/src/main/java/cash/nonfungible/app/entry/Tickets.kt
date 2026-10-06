package cash.nonfungible.app.entry

import android.content.Context
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/**
 * One NFT of a collection, as the collection's page lists it. `sent`: it has been passed on.
 * `id` is what the site calls this collection's card of it.
 */
data class Ticket(
    val h: String,
    val title: String,
    val created: Long,
    val sent: Boolean = false,
    val id: String = "",
)

/**
 * The NFTs a collection's page listed when the door last read it: the tickets this door admits.
 * A page lists every NFT its collection has held, sold on or not, so a ticket stays on it when
 * it changes hands. The list is kept on the phone so that it can be read at the door and a
 * check need not download it again.
 */
class Tickets(private val context: Context, collection: Collection) {
    private val name = "tickets-${collection.key}"
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    val all: List<Ticket>
        get() = try {
            listed(JsonParser.parseString(prefs.getString(LIST, null) ?: "[]") as? JsonArray)
        } catch (e: JsonParseException) {
            emptyList()
        }

    operator fun contains(h: String): Boolean = all.any { it.h == h }

    /**
     * Keeps what the page lists now, one entry for each asset. A page that lists an asset twice
     * has passed it on and got it back: it is held.
     */
    fun save(cards: JsonArray) {
        val listed = listed(cards)
        val held = listed.filterNot(Ticket::sent).associateBy(Ticket::h)
        keep(listed.distinctBy(Ticket::h).map { held[it.h] ?: it })
    }

    /** Keeps one more NFT ahead of the page being read again: one this phone has just been given. */
    fun add(ticket: Ticket) = keep(all.filterNot { it.h == ticket.h } + ticket)

    private fun keep(tickets: List<Ticket>) {
        val kept = JsonArray()
        for (ticket in tickets) {
            kept.add(JsonObject().apply {
                addProperty("h", ticket.h)
                addProperty("title", ticket.title)
                addProperty("created", ticket.created)
                if (ticket.sent) addProperty("status", "sent")
                addProperty("id", ticket.id)
            })
        }
        prefs.edit().putString(LIST, kept.toString()).apply()
    }

    fun delete() {
        context.deleteSharedPreferences(name)
    }

    private fun listed(cards: JsonArray?): List<Ticket> =
        (cards ?: JsonArray()).filterIsInstance<JsonObject>().mapNotNull { card ->
            val h = card.get("h").text()?.takeIf(HASH::matches) ?: return@mapNotNull null
            val created = card.get("created").whole()
            val sent = card.get("status").text() == "sent"
            Ticket(h, card.get("title").text().orEmpty(), created ?: 0, sent, card.get("id").text().orEmpty())
        }

    private companion object {
        const val LIST = "list"
        val HASH = Regex("[0-9a-f]{64}")
    }
}
