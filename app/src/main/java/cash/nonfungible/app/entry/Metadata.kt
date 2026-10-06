package cash.nonfungible.app.entry

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonPrimitive
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader
import java.math.BigDecimal
import java.nio.ByteBuffer

/** Its number in its collection, what it is out of, and the name of the run it was made in. One made after the set is numbered past it. */
data class Edition(val number: Long, val of: Long? = null, val name: String? = null)

/** What an attribute holds: text, a whole number, or yes or no. */
sealed class Value {
    data class Text(val text: String) : Value()
    data class Whole(val number: Long) : Value()
    data class Truth(val holds: Boolean) : Value()
}

/** One thing an NFT says about itself: "Weight", and 125 with one decimal place in kg. */
data class Attribute(
    val trait: String,
    val value: Value,
    /** `text`, `number`, `boolean` or `date`: what it says it is, or else what its value is. */
    val type: String,
    val max: Long? = null,
    val dp: Int? = null,
    val unit: String? = null,
) {
    /**
     * The value as it is read: "12.5 of 20 kg", "Yes". A date is seconds since the epoch, and
     * is shown as that number when `date` cannot write it.
     */
    fun shown(yes: String, no: String, date: (Long) -> String? = { null }): String = when (value) {
        is Value.Text -> value.text
        is Value.Truth -> if (value.holds) yes else no
        is Value.Whole ->
            if (type == "date") date(value.number) ?: value.number.toString()
            else listOfNotNull(scaled(value.number), max?.let { "of ${scaled(it)}" }, unit).joinToString(" ")
    }

    private fun scaled(number: Long): String = BigDecimal.valueOf(number, dp ?: 0).toPlainString()
}

/** The collection an NFT says it is of. Anybody can write any: the site that holds it bears it out. */
data class Claimed(val id: String, val url: String? = null, val name: String? = null)

/** Something elsewhere that goes with the NFT. The app never fetches it by itself. */
data class Link(
    val rel: String,
    val url: String,
    val type: String? = null,
    val sha256: String? = null,
    val title: String? = null,
)

/** What a ticket says of its event. All of it may be left out. */
data class Pass(val starts: Long? = null, val ends: Long? = null, val venue: String? = null)

/**
 * What an image NFT says about itself: an `nft-metadata/2` document. An NFT need not say
 * anything, and a document that breaks a rule of its facts says nothing; a part beside the
 * facts that breaks a rule is left out and the rest stands.
 */
data class Metadata(
    val name: String,
    val description: String? = null,
    /** The kind of NFT it is within its collection: "VIP", "Fox". */
    val kind: String? = null,
    val edition: Edition? = null,
    val attributes: List<Attribute> = emptyList(),
    /** The extensions whose rules whoever reads it must follow; all of them are known here. */
    val must: List<String> = emptyList(),
    val collection: Claimed? = null,
    val links: List<Link> = emptyList(),
    val locale: String? = null,
    /** How it asks to be drawn, which is left to the site: kept as written. */
    val style: JsonObject? = null,
    /** Set when it says it is a ticket: the door of its collection admits it. */
    val ticket: Pass? = null,
) {
    /** Whether it says the door of the collection with this key admits it. */
    fun opens(pubkey: String): Boolean = ticket != null && collection?.id == pubkey

    /** The document as every reader gives it back, which the shared vectors are written in. */
    fun normal(): JsonObject = JsonObject().apply {
        addProperty("schema", SCHEMA)
        addProperty("name", name)
        description?.let { addProperty("description", it) }
        kind?.let { addProperty("kind", it) }
        edition?.let { edition ->
            add("edition", JsonObject().apply {
                addProperty("number", edition.number)
                edition.of?.let { addProperty("of", it) }
                edition.name?.let { addProperty("name", it) }
            })
        }
        add("attributes", JsonArray().apply {
            for (attribute in attributes) {
                add(JsonObject().apply {
                    addProperty("trait_type", attribute.trait)
                    when (val value = attribute.value) {
                        is Value.Text -> addProperty("value", value.text)
                        is Value.Whole -> addProperty("value", value.number)
                        is Value.Truth -> addProperty("value", value.holds)
                    }
                    addProperty("type", attribute.type)
                    attribute.max?.let { addProperty("max_value", it) }
                    attribute.dp?.let { addProperty("dp", it) }
                    attribute.unit?.let { addProperty("unit", it) }
                })
            }
        })
        if (must.isNotEmpty()) add("must", JsonArray().apply { must.forEach(::add) })
        collection?.let { claimed ->
            add("collection", JsonObject().apply {
                addProperty("id", claimed.id)
                claimed.url?.let { addProperty("url", it) }
                claimed.name?.let { addProperty("name", it) }
            })
        }
        if (links.isNotEmpty()) {
            add("links", JsonArray().apply {
                for (link in links) {
                    add(JsonObject().apply {
                        addProperty("rel", link.rel)
                        addProperty("url", link.url)
                        link.type?.let { addProperty("type", it) }
                        link.sha256?.let { addProperty("sha256", it) }
                        link.title?.let { addProperty("title", it) }
                    })
                }
            })
        }
        locale?.let { addProperty("locale", it) }
        style?.let { add("style", it) }
        ticket?.let { pass ->
            add("ext", JsonObject().apply {
                add("ticket", JsonObject().apply {
                    pass.starts?.let { addProperty("starts", it) }
                    pass.ends?.let { addProperty("ends", it) }
                    pass.venue?.let { addProperty("venue", it) }
                })
            })
        }
    }

    companion object {
        private const val SCHEMA = "nft-metadata/2"
        private const val MOST = 9007199254740991L
        private val JSON = Gson().getAdapter(JsonElement::class.java)
        private val WHOLE = Regex("-?(0|[1-9][0-9]{0,15})")
        private val BARRED = Regex("[\\u0000-\\u001f\\u007f\\u061c\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]")
        private val LABEL = Regex("[a-z][a-z0-9-]{0,31}")
        private const val PART = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
        private val EXTENSION = Regex("[a-z][a-z0-9-]{0,31}|$PART(\\.$PART)+/[a-z][a-z0-9-]{0,31}")
        private val KNOWN = setOf("ticket")
        private val TYPES = setOf("text", "number", "boolean", "date")
        private val HASH = Regex("[0-9a-f]{64}")
        private const val HOST = "[a-z0-9.-]{1,253}(:[0-9]{1,5})?"
        private const val PATH = "(/[A-Za-z0-9._~/%-]{0,300})?"
        private const val REST = "(/[A-Za-z0-9._~/%?#&=+:,;@!$'()*-]{0,300})?"
        private val PAGE = Regex("https://$HOST$PATH|http://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?$PATH")
        private val ELSEWHERE = Regex("https://$HOST$REST|ipfs://[A-Za-z0-9]{1,100}$REST")
        private val MEDIA = Regex("[a-z0-9.+-]{1,40}/[a-z0-9.+-]{1,60}")
        private val LOCALE = Regex("[a-z]{2,3}(-[A-Za-z0-9]{2,8}){0,2}")
        private val COLOUR = Regex("#[0-9a-fA-F]{6}")
        private val COLOURS = listOf("ground", "panel", "ink", "accent")
        private val TEMPLATE = Regex("[a-z][a-z0-9-]{0,31}/[0-9]{1,3}")

        /** The metadata a JPEG carries in the `nftm` tag of its colour profile, or null. */
        fun read(jpg: ByteArray): Metadata? = embedded(jpg)?.let(::parse)

        /** The text of the document a JPEG carries, when it reads as metadata: what is kept of it. */
        fun document(jpg: ByteArray): String? =
            embedded(jpg)?.takeIf { parse(it) != null }?.let { String(it, Charsets.US_ASCII) }

        /** The metadata a tag's text holds, or null when it breaks a rule. */
        fun parse(body: ByteArray): Metadata? {
            if (!wellWritten(body)) return null
            val document = try {
                // Gson's own entry points read leniently; a strict reader holds to JSON.
                val reader = JsonReader(StringReader(String(body, Charsets.US_ASCII)))
                JSON.read(reader).takeIf { reader.peek() == JsonToken.END_DOCUMENT }
            } catch (e: JsonParseException) {
                null
            } catch (e: IOException) {
                null
            } catch (e: IllegalStateException) {
                null
            } as? JsonObject ?: return null
            if (document.get("schema").string() != SCHEMA) return null
            val name = document.get("name").text(80) ?: return null
            val description = document.get("description")?.let { it.text(1000, lines = true) ?: return null }
            val kind = document.get("kind")?.let { it.text(40) ?: return null }
            val edition = document.get("edition")?.let { edition(it) ?: return null }
            val attributes = document.get("attributes")?.let { given ->
                val list = (given as? JsonArray)?.takeIf { it.size() <= 32 } ?: return null
                list.map { attribute(it) ?: return null }
            }
            val must = document.get("must")?.let { given ->
                val list = (given as? JsonArray)?.takeIf { it.size() <= 8 } ?: return null
                list.map { it.string()?.takeIf(EXTENSION::matches) ?: return null }
            }
            // An NFT that depends on rules this reader cannot follow says nothing to it.
            if (must.orEmpty().any { it !in KNOWN }) return null
            val collection = claimed(document.get("collection"))
            val entries = document.get("ext") as? JsonObject
            return Metadata(
                name, description, kind, edition, attributes.orEmpty(), must.orEmpty(), collection,
                links(document.get("links")),
                document.get("locale").string()?.takeIf(LOCALE::matches),
                style(document.get("style")),
                // A ticket is for some door: with no collection it is not one.
                (entries?.get("ticket") as? JsonObject)?.takeIf { collection != null }?.let(::pass),
            )
        }

        /**
         * The rules a document keeps as text, wherever in it: its size and bytes, numbers
         * written as plain whole ones, and how deep it nests. They are read off the text so
         * that nothing hangs on what Gson rounds or lets pass, which is also why words and
         * escapes are held to JSON's own here.
         */
        private fun wellWritten(body: ByteArray): Boolean {
            if (body.isEmpty() || body.size > 16384 || body.any { it < 0x20 || it > 0x7e }) return false
            val text = String(body, Charsets.US_ASCII)
            var at = 0
            var depth = 0
            fun run(among: (Char) -> Boolean): String {
                val from = at
                while (at < text.length && among(text[at])) at++
                return text.substring(from, at)
            }
            while (at < text.length) {
                val next = text[at]
                when {
                    next == '"' -> {
                        at++
                        while (at < text.length && text[at] != '"') {
                            if (text[at] != '\\') {
                                at++
                                continue
                            }
                            val escaped = text.getOrNull(at + 1) ?: return false
                            if (escaped !in "\"\\/bfnrtu") return false
                            at += 2
                            if (escaped == 'u' && run { it in "0123456789abcdefABCDEF" }.length < 4) return false
                        }
                        if (at++ >= text.length) return false
                    }
                    next == '{' || next == '[' -> {
                        if (++depth > 32) return false
                        at++
                    }
                    next == '}' || next == ']' -> {
                        depth--
                        at++
                    }
                    next == ',' || next == ':' || next == ' ' -> at++
                    next == '-' || next in '0'..'9' -> {
                        val number = run { it in "+-.eE" || it in '0'..'9' }
                        if (!WHOLE.matches(number) || number == "-0") return false
                        if (Math.abs(number.toLong()) > MOST) return false
                    }
                    else -> if (run { it in 'a'..'z' } !in setOf("true", "false", "null")) return false
                }
            }
            return true
        }

        private fun edition(given: JsonElement): Edition? {
            val edition = given as? JsonObject ?: return null
            val number = edition.get("number").whole()?.takeIf { it >= 1 } ?: return null
            val of = edition.get("of")?.let { it.whole()?.takeIf { of -> of >= 1 } ?: return null }
            return Edition(number, of, edition.get("name")?.let { it.text(40) ?: return null })
        }

        private fun attribute(given: JsonElement): Attribute? {
            val attribute = given as? JsonObject ?: return null
            val trait = attribute.get("trait_type").text(40) ?: return null
            val held = attribute.get("value") as? JsonPrimitive ?: return null
            val value = when {
                held.isBoolean -> Value.Truth(held.asBoolean)
                held.isNumber -> Value.Whole(held.whole() ?: return null)
                else -> Value.Text(held.text(120) ?: return null)
            }
            val plain = when (value) {
                is Value.Text -> "text"
                is Value.Whole -> "number"
                is Value.Truth -> "boolean"
            }
            // A type this reader does not know is read as if it were not there.
            val said = attribute.get("type")?.let { it.string()?.takeIf(LABEL::matches) ?: return null }
                ?.takeIf { it in TYPES }
            if (said != null && said != plain && !(said == "date" && value is Value.Whole)) return null
            val type = said ?: plain
            val max = attribute.get("max_value")?.let { it.whole() ?: return null }
            val dp = attribute.get("dp")?.let { it.whole()?.takeIf { dp -> dp in 0..9 }?.toInt() ?: return null }
            val unit = attribute.get("unit")?.let { it.text(12) ?: return null }
            if (type != "number" && (max != null || dp != null || unit != null)) return null
            return Attribute(trait, value, type, max, dp, unit)
        }

        private fun claimed(given: JsonElement?): Claimed? {
            val claimed = given as? JsonObject ?: return null
            val id = claimed.get("id").string()?.takeIf(HASH::matches) ?: return null
            val url = claimed.get("url")?.let { it.string()?.takeIf(PAGE::matches) ?: return null }
            return Claimed(id, url, claimed.get("name")?.let { it.text(80) ?: return null })
        }

        /** One link that breaks a rule and none of them are kept. */
        private fun links(given: JsonElement?): List<Link> {
            val list = (given as? JsonArray)?.takeIf { it.size() in 1..8 } ?: return emptyList()
            return list.map { entry ->
                val link = entry as? JsonObject ?: return emptyList()
                Link(
                    link.get("rel").string()?.takeIf(LABEL::matches) ?: return emptyList(),
                    link.get("url").string()?.takeIf(ELSEWHERE::matches) ?: return emptyList(),
                    link.get("type")?.let { it.string()?.takeIf(MEDIA::matches) ?: return emptyList() },
                    link.get("sha256")?.let { it.string()?.takeIf(HASH::matches) ?: return emptyList() },
                    link.get("title")?.let { it.text(80) ?: return emptyList() },
                )
            }
        }

        private fun style(given: JsonElement?): JsonObject? {
            val style = given as? JsonObject ?: return null
            return JsonObject().apply {
                style.get("palette")?.let { colours ->
                    val palette = colours as? JsonObject ?: return null
                    add("palette", JsonObject().apply {
                        for (colour in COLOURS) {
                            addProperty(colour, palette.get(colour).string()?.takeIf(COLOUR::matches) ?: return null)
                        }
                    })
                }
                style.get("template")?.let {
                    addProperty("template", it.string()?.takeIf(TEMPLATE::matches) ?: return null)
                }
                style.get("params")?.let { add("params", it as? JsonObject ?: return null) }
            }
        }

        private fun pass(ticket: JsonObject): Pass? {
            val starts = ticket.get("starts")?.let { it.whole()?.takeIf { at -> at >= 0 } ?: return null }
            val ends = ticket.get("ends")?.let { it.whole()?.takeIf { at -> at >= 0 } ?: return null }
            if (starts != null && ends != null && ends < starts) return null
            return Pass(starts, ends, ticket.get("venue")?.let { it.text(120) ?: return null })
        }

        private fun JsonElement?.string(): String? =
            (this as? JsonPrimitive)?.takeIf { it.isString }?.asString

        /** True and false are not numbers, and the text has been held to plain whole ones. */
        private fun JsonElement?.whole(): Long? =
            (this as? JsonPrimitive)?.takeIf { it.isNumber }?.asString?.toLongOrNull()

        /**
         * Text is 1 to `most` code points, well formed, without control characters or the marks
         * that turn the direction of writing, and not only spaces. A description has lines.
         */
        private fun JsonElement?.text(most: Int, lines: Boolean = false): String? = string()?.takeIf { text ->
            val plain = if (lines) text.replace("\n", "") else text
            plain.any { it != ' ' } && !BARRED.containsMatchIn(plain) && text.wellFormed() &&
                text.codePointCount(0, text.length) <= most
        }

        private fun String.wellFormed(): Boolean {
            var at = 0
            while (at < length) {
                val high = this[at].isHighSurrogate()
                if (this[at].isLowSurrogate()) return false
                if (high && (at + 1 >= length || !this[at + 1].isLowSurrogate())) return false
                at += if (high) 2 else 1
            }
            return true
        }

        /**
         * A mint re-encodes every upload and keeps only its pixels and colour profile, so the
         * profile is where metadata lasts. The first `nftm` tag is the document.
         */
        private fun embedded(jpg: ByteArray): ByteArray? {
            val parts = HashMap<Int, ByteArray>()
            var at = 2
            fun byte(index: Int) = jpg[index].toInt() and 0xff
            while (at + 4 <= jpg.size && byte(at) == 0xff && byte(at + 1) != 0xda) {
                val length = byte(at + 2) shl 8 or byte(at + 3)
                if (length < 2 || at + 2 + length > jpg.size) return null
                val named = length > 16 && String(jpg, at + 4, 12, Charsets.ISO_8859_1) == ICC
                if (byte(at + 1) == 0xe2 && named) {
                    parts[byte(at + 16)] = jpg.copyOfRange(at + 18, at + 2 + length)
                }
                at += 2 + length
            }
            if (parts.isEmpty()) return null
            val profile = (1..parts.keys.max()).flatMap { (parts[it] ?: return null).asIterable() }
                .toByteArray()
            if (profile.size < 132) return null
            val view = ByteBuffer.wrap(profile)
            val count = Integer.toUnsignedLong(view.getInt(128)).coerceAtMost(256).toInt()
            for (entry in 132 until 132 + 12 * count step 12) {
                if (entry + 12 > profile.size) break
                if (String(profile, entry, 4, Charsets.ISO_8859_1) != "nftm") continue
                val start = Integer.toUnsignedLong(view.getInt(entry + 4))
                val size = Integer.toUnsignedLong(view.getInt(entry + 8))
                if (size < 8 || start + size > profile.size) return null
                if (String(profile, start.toInt(), 4, Charsets.ISO_8859_1) != "text") return null
                var end = (start + size).toInt()
                while (end > start + 8 && profile[end - 1].toInt() == 0) end--
                return profile.copyOfRange(start.toInt() + 8, end)
            }
            return null
        }

        private const val ICC = "ICC_PROFILE\u0000"
    }
}
