package cash.nonfungible.app.entry

import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The door reads what an NFT says about itself as every other reader does: the vectors are
 * cashu-nft's tests/nft_metadata_vectors.json, which the Python and browser readers answer to.
 */
class MetadataTest {

    private val vectors: JsonArray =
        checkNotNull(javaClass.getResourceAsStream("/nft_metadata_vectors.json"))
            .reader().use { JsonParser.parseReader(it).asJsonArray }

    @Test
    fun `every vector reads as the other readers read it`() {
        val wrong = vectors.map { it.asJsonObject }.mapNotNull { vector ->
            // The text is the tag's bytes, one character for each.
            val read = Metadata.parse(vector.get("text").asString.toByteArray(Charsets.ISO_8859_1))?.normal()
            val expected = vector.get("metadata").takeUnless { it is JsonNull }
            "${vector.get("case").asString}: $read, not $expected".takeIf { read != expected }
        }
        assertEquals(wrong.joinToString("\n"), 0, wrong.size)
    }

    @Test
    fun `a ticket's JPEG says what it is, and a plain picture says nothing`() {
        val ticket = checkNotNull(Metadata.read(Vectors.asset))
        assertEquals("An Evening", ticket.name)
        assertEquals("General", ticket.kind)
        assertEquals(Edition(2, 5), ticket.edition)
        // It says it is a ticket, and for which collection: the one the door's vectors ask for.
        assertTrue(ticket.opens("ab".repeat(32)))
        assertFalse(ticket.opens("cd".repeat(32)))
        assertEquals(ticket, Metadata.document(Vectors.asset)?.let { Metadata.parse(it.toByteArray()) })
        assertNull(Metadata.read(Vectors.plain))
        assertNull(Metadata.read(ByteArray(0)))
        assertNull(Metadata.read(Vectors.asset.copyOf(600)))
        // A picture that says it in the format before this one says nothing now.
        val before = String(Vectors.asset, Charsets.ISO_8859_1).replace("nft-metadata/2", "nft-metadata/1")
        assertNull(Metadata.read(before.toByteArray(Charsets.ISO_8859_1)))
    }

    @Test
    fun `a number is shown to its decimal places, with what it is out of and its unit`() {
        fun number(value: Long, max: Long? = null, dp: Int? = null, unit: String? = null) =
            Attribute("Weight", Value.Whole(value), "number", max, dp, unit).shown("Yes", "No")
        assertEquals("125", number(125))
        assertEquals("12.5 kg", number(125, dp = 1, unit = "kg"))
        assertEquals("12.0", number(120, dp = 1))
        assertEquals("0.005", number(5, dp = 3))
        assertEquals("-0.5", number(-5, dp = 1))
        assertEquals("2 of 3", number(2, max = 3))
        assertEquals("12.5 of 20.0 kg", number(125, max = 200, dp = 1, unit = "kg"))
    }

    @Test
    fun `yes or no is said in the reader's words, and a date nobody can write is its number`() {
        assertEquals("Ja", Attribute("Shiny", Value.Truth(true), "boolean").shown("Ja", "Nein"))
        assertEquals("Nein", Attribute("Shiny", Value.Truth(false), "boolean").shown("Ja", "Nein"))
        assertEquals("Fire", Attribute("Element", Value.Text("Fire"), "text").shown("Yes", "No"))
        val hatched = Attribute("Hatched", Value.Whole(1767225600), "date")
        assertEquals("1767225600", hatched.shown("Yes", "No"))
        assertEquals("1 Jan 2026", hatched.shown("Yes", "No") { "1 Jan 2026" })
        assertEquals("1767225600", hatched.shown("Yes", "No") { null })
    }

    @Test
    fun `a ticket opens the door of the collection it names and no other`() {
        val key = "2e".repeat(32)
        val ticket = Metadata("An Evening", collection = Claimed(key), ticket = Pass())
        assertEquals(true, ticket.opens(key))
        assertEquals(false, ticket.opens("3f".repeat(32)))
        assertEquals(false, ticket.copy(ticket = null).opens(key))
    }
}
