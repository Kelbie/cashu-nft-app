package cash.nonfungible.app.studio

import android.content.Context
import cash.nonfungible.app.entry.text
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * A kind of NFT a template draws: what it is called, what the template is told about it, and
 * how many of it the template suggests making at first, when it suggests a number.
 */
data class Kind(val label: String, val options: JsonObject, val count: Int? = null)

/**
 * A template: a scene that draws a picture for each NFT, and the kinds it suggests. The app
 * brings a few; a holder adds their own by pasting a scene somebody, or something, wrote.
 */
data class Template(
    val id: String,
    val name: String,
    val edition: String,
    val kinds: List<Kind>,
    val own: Boolean,
    /** Whether its NFTs are tickets, which say whose doors they open. Art and collectibles are not. */
    val ticket: Boolean = true,
)

/**
 * The templates on this phone: the app's, then the holder's own. Of the app's, those that
 * draw tickets come first, so a new collection starts as tickets.
 */
class Templates(private val context: Context) {
    private val own = File(context.filesDir, "templates")

    val all: List<Template>
        get() = brought.mapNotNull { read(it, false) }.sortedWith(compareBy({ !it.ticket }, { it.name })) +
            own.list().orEmpty().sorted().mapNotNull { read(it, true) }

    /** The templates the app brings, as its build lists them. */
    private val brought: List<String>
        get() = try {
            context.assets.open("$BUILT_IN/index.json").reader().use { JsonParser.parseReader(it) }
                .let { it as? JsonArray }?.mapNotNull { it.text() }.orEmpty()
        } catch (e: IOException) {
            emptyList()
        }

    fun named(id: String): Template? = all.firstOrNull { it.id == id }

    /** A file of a template, as its page asks for it: the scene, or something the scene loads. */
    fun open(id: String, path: String): InputStream? = try {
        // A page's address cannot climb out of its template.
        if (!ID.matches(id) || ".." in path) null
        else if (File(own, id).isDirectory) File(File(own, id), path).inputStream()
        else context.assets.open("$BUILT_IN/$id/$path")
    } catch (e: IOException) {
        null
    }

    /** Keeps a scene the holder brought. It draws one kind until they name more. */
    fun add(name: String, scene: String): Template {
        val id = "own-" + java.lang.Long.toString(System.currentTimeMillis(), 36)
        val folder = File(own, id).apply { mkdirs() }
        File(folder, "scene.js").writeText(scene)
        val note = JsonObject().apply {
            addProperty("name", name)
            add("kinds", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("label", "General admission")
                    add("options", JsonObject())
                })
            })
        }
        File(folder, NOTE).writeText(note.toString())
        return checkNotNull(read(id, true))
    }

    /**
     * Takes what a scene says its collection is made of: its kinds, its edition and whether its
     * NFTs are tickets. The scene's word is only as good as its page, so it is read as any note is.
     */
    fun describe(template: Template, said: JsonObject): Template {
        if (!template.own) return template
        val note = JsonObject().apply { addProperty("name", template.name) }
        for (member in listOf("ticket", "edition", "kinds")) said.get(member)?.let { note.add(member, it) }
        val written = File(File(own, template.id), NOTE)
        val before = written.readText()
        written.writeText(note.toString())
        // A note that names no kind is no template: the one it had stands.
        return read(template.id, true) ?: template.also { written.writeText(before) }
    }

    fun remove(template: Template) {
        if (template.own) File(own, template.id).deleteRecursively()
    }

    private fun read(id: String, own: Boolean): Template? = try {
        val note = open(id, NOTE)?.reader()?.use { JsonParser.parseReader(it) } as? JsonObject
        val kinds = (note?.get("kinds") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty().take(MOST_KINDS)
            .mapNotNull { kind ->
                val count = (kind.get("count") as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt?.coerceIn(0, MOST)
                kind.get("label").text()?.takeIf { it.isNotBlank() }
                    ?.let { Kind(it.take(LABEL), kind.get("options") as? JsonObject ?: JsonObject(), count) }
            }
        // Tickets unless it says otherwise.
        val ticket = (note?.get("ticket") as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean ?: true
        note?.get("name").text()?.takeIf { kinds.isNotEmpty() }
            ?.let { Template(id, it, note?.get("edition").text().orEmpty().take(EDITION), kinds, own, ticket) }
    } catch (e: JsonParseException) {
        null
    } catch (e: NumberFormatException) {
        null
    }

    companion object {
        private const val BUILT_IN = "studio/templates"
        private const val NOTE = "template.json"

        /** The most of one kind made at a time, and the most kinds, a label and an edition come to. */
        const val MOST = 200
        private const val MOST_KINDS = 40
        private const val LABEL = 24
        private const val EDITION = 28

        /** A template's id is part of its page's address. */
        val ID = Regex("[a-z0-9][a-z0-9-]{0,39}")
    }
}
