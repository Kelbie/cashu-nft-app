package cash.nonfungible.app

import android.content.Context
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.text
import cash.nonfungible.app.studio.Made
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/**
 * A collection an account keeps, and what the collection calls itself. `mine` when the account
 * made it and so holds its key: one that was only added, to keep a door for, is not, even on a
 * phone where another account made it.
 */
data class Saved(val collection: Collection, val name: String, val mine: Boolean = false)

/** The collections an account made or keeps a door for, and the one it last had open. */
class Collections(private val context: Context, private val account: Account) {
    private val prefs = context.getSharedPreferences(account.store("collections"), Context.MODE_PRIVATE)

    init {
        // What an earlier build kept under names two collections could share is moved, once,
        // to names they cannot: a record of who came in must not be lost or lent.
        for (saved in all) {
            for (kept in OLD) {
                val old = "$kept-${saved.collection.url.replace(Regex("[^a-z0-9]+"), "_")}"
                val earlier = context.getSharedPreferences(old, Context.MODE_PRIVATE).all
                if (earlier.isEmpty()) continue
                val now = context.getSharedPreferences("$kept-${saved.collection.key}", Context.MODE_PRIVATE)
                if (now.all.isEmpty()) {
                    val moved = now.edit()
                    for ((name, value) in earlier) {
                        when (value) {
                            is String -> moved.putString(name, value)
                            is Int -> moved.putInt(name, value)
                        }
                    }
                    moved.commit()
                }
                context.deleteSharedPreferences(old)
            }
        }
    }

    val all: List<Saved>
        get() = try {
            (JsonParser.parseString(prefs.getString(LIST, null) ?: "[]") as? JsonArray)
                ?.filterIsInstance<JsonObject>().orEmpty().mapNotNull { saved ->
                    val collection = saved.get("url").text()?.let(Collection::parse) ?: return@mapNotNull null
                    // A list written before accounts said nothing of whose a collection was: it
                    // was whoever's phone had made it. Only the first account has such a list.
                    val mine = saved.get("mine")?.takeIf { it.isJsonPrimitive }?.asBoolean
                        ?: (account.id == Account.FIRST && Made(context, collection).template != null)
                    Saved(collection, saved.get("name").text().orEmpty(), mine)
                }
        } catch (e: JsonParseException) {
            emptyList()
        }

    /** The collection on the door: the one chosen, or the first while none is. */
    val selected: Saved?
        get() = all.let { saved ->
            saved.firstOrNull { it.collection.url == prefs.getString(SELECTED, null) }
                ?: saved.firstOrNull()
        }

    /**
     * Keeps a collection, or its new name, and makes it the one in hand. `mine` for one the
     * account has just made; one it already keeps stays whose it was.
     */
    fun add(collection: Collection, name: String, mine: Boolean = false) {
        val kept = all
        val was = kept.firstOrNull { it.collection == collection }?.mine ?: false
        save(kept.filterNot { it.collection == collection } + Saved(collection, name, mine || was), collection)
    }

    fun select(collection: Collection) {
        prefs.edit().putString(SELECTED, collection.url).apply()
    }

    /**
     * Takes a collection off the account's list. What the phone keeps about the collection
     * itself is not the list's to delete: another account may keep it too. See `App.forget`.
     */
    fun remove(collection: Collection) {
        save(all.filterNot { it.collection == collection }, selected?.collection)
    }

    private fun save(saved: List<Saved>, selected: Collection?) {
        val list = JsonArray()
        for (one in saved) {
            list.add(JsonObject().apply {
                addProperty("url", one.collection.url)
                addProperty("name", one.name)
                addProperty("mine", one.mine)
            })
        }
        prefs.edit().putString(LIST, list.toString()).putString(SELECTED, selected?.url).apply()
    }

    private companion object {
        val OLD = listOf("tickets", "admitted", "made")
        const val LIST = "list"
        const val SELECTED = "selected"
    }
}
