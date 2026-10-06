package cash.nonfungible.app

import cash.nonfungible.app.entry.Attribute
import cash.nonfungible.app.entry.Edition
import cash.nonfungible.app.entry.Metadata
import cash.nonfungible.app.entry.Ticket
import cash.nonfungible.app.entry.Value
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the phone says about an NFT: its type and its number, and the order a list is in. */
class DescribedTest {

    private fun nft(title: String, said: Metadata? = null): Described =
        Described(title.hashCode().toString(), Ticket(title.hashCode().toString(), title, 0), said?.let { About(it, 1, 1, 1, "JPEG") })

    @Test
    fun `an NFT says its kind and its number, and one that says nothing is taken at its title`() {
        val said = nft("whatever", Metadata("An Evening", kind = "Crew", edition = Edition(3, 50, "first")))
        assertEquals("Crew", said.type)
        assertEquals(3, said.number)
        val titled = nft("General admission · 12 of 50")
        assertEquals("General admission", titled.type)
        assertEquals(12, titled.number)
        val plain = nft("a picture of a cat")
        assertNull(plain.type)
        assertNull(plain.number)
    }

    @Test
    fun `a row leads with what tells an NFT from the others, and its name and attributes follow`() {
        val attributes = listOf(
            Attribute("Guest", Value.Text("Ada"), "text"),
            Attribute("Shiny", Value.Truth(true), "boolean"),
            Attribute("Weight", Value.Whole(125), "number", dp = 1, unit = "kg"),
        )
        val full = nft("x", Metadata("An Evening", kind = "Crew", edition = Edition(3, 50), attributes = attributes))
        assertEquals("Crew · 3 of 50", full.lead)
        assertEquals("An Evening · Ada · Shiny", full.rest)
        assertEquals("Crew · #3", nft("x", Metadata("An Evening", kind = "Crew", edition = Edition(3))).lead)
        assertEquals("Crew", nft("x", Metadata("An Evening", kind = "Crew")).lead)
        val quiet = attributes.map { it.copy(value = Value.Truth(false), type = "boolean", dp = null, unit = null) }
        val named = nft("x", Metadata("An Evening", attributes = quiet.take(1) + Attribute("Made", Value.Whole(0), "date") + attributes[2]))
        assertEquals("An Evening", named.lead)
        assertEquals("12.5 kg", named.rest)
        val plain = nft("Crew · 3 of 50")
        assertEquals("Crew · 3 of 50", plain.lead)
        assertEquals("", plain.rest)
    }

    @Test
    fun `a list is in the order tickets are counted, not the order their titles are spelt`() {
        val listed = listOf(
            nft("General · 10 of 12"), nft("General · 2 of 12"), nft("a picture"), nft("Crew · 1 of 12"), nft("General · 9 of 12"),
        ).sortedWith(Described.ORDER)
        assertEquals(listOf(1, 2, 9, 10, null), listed.map { it.number })
    }
}
