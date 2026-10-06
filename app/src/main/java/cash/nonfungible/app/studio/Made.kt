package cash.nonfungible.app.studio

import android.content.Context
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.text
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * What the phone remembers of a collection it made: the template that draws its NFTs, the
 * kinds it has, and how far it has numbered them, so that more can be issued later and carry
 * on where the last left off.
 */
class Made(private val context: Context, collection: Collection) {
    private val name = "made-${collection.key}"
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    val template: String? get() = prefs.getString(TEMPLATE, null)
    val edition: String get() = prefs.getString(EDITION, null).orEmpty()

    /** How many NFTs have been issued: the next is numbered one more. */
    var issued: Int
        get() = prefs.getInt(ISSUED, 0)
        set(count) = prefs.edit().putInt(ISSUED, count).apply()

    /**
     * What its NFTs say they are "of": the count of its first run. One minted later is numbered
     * past it, as a card printed after a set is, so that no NFT's picture is made a lie of.
     * Zero until the first run is minted.
     */
    var total: Int
        get() = prefs.getInt(TOTAL, 0)
        set(count) = prefs.edit().putInt(TOTAL, count).apply()

    /**
     * How many NFTs of a kind have been issued: the next of that kind is one more among its own,
     * which is what its template is told as its place. A collection made before the app kept
     * this counts each kind from its next NFT.
     */
    fun issuedOf(label: String): Int = places.getOrDefault(label, 0)

    /** One more of a kind has been issued. */
    fun count(label: String) {
        val counted = JsonObject()
        for ((kind, issued) in places + (label to issuedOf(label) + 1)) counted.addProperty(kind, issued)
        prefs.edit().putString(PLACES, counted.toString()).apply()
    }

    private val places: Map<String, Int>
        get() = try {
            (JsonParser.parseString(prefs.getString(PLACES, null) ?: "{}") as? JsonObject)?.entrySet().orEmpty()
                .mapNotNull { (kind, issued) ->
                    (issued as? JsonPrimitive)?.takeIf { it.isNumber }?.let { kind to it.asInt }
                }.toMap()
        } catch (e: JsonParseException) {
            emptyMap()
        } catch (e: NumberFormatException) {
            emptyMap()
        }

    val kinds: List<Kind>
        get() = try {
            (JsonParser.parseString(prefs.getString(KINDS, null) ?: "[]") as? JsonArray)
                ?.filterIsInstance<JsonObject>().orEmpty().mapNotNull { kind ->
                    kind.get("label").text()?.let { Kind(it, kind.get("options") as? JsonObject ?: JsonObject()) }
                }
        } catch (e: JsonParseException) {
            emptyList()
        }

    fun save(template: Template, kinds: List<Kind>) {
        val kept = JsonArray()
        for (kind in kinds) {
            kept.add(JsonObject().apply {
                addProperty("label", kind.label)
                add("options", kind.options)
            })
        }
        prefs.edit().putString(TEMPLATE, template.id).putString(EDITION, template.edition)
            .putString(KINDS, kept.toString()).apply()
    }

    fun delete() {
        context.deleteSharedPreferences(name)
    }

    private companion object {
        const val TEMPLATE = "template"
        const val EDITION = "edition"
        const val ISSUED = "issued"
        const val TOTAL = "total"
        const val KINDS = "kinds"
        const val PLACES = "places"
    }
}
