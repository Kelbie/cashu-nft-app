package cash.nonfungible.app.studio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cash.nonfungible.app.entry.Admitted
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Tickets
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** What a collection charges, how that changes with the days, and what it puts on sale. */
@RunWith(RobolectricTestRunner::class)
class SellingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val evening = checkNotNull(Collection.parse("https://mint.example/p/${"ab".repeat(32)}"))
    private val early = mapOf("General" to 50_000L, "VIP" to 150_000L)
    private val regular = Step(1_000, mapOf("General" to 75_000L, "VIP" to 200_000L))
    private val door = Step(2_000, mapOf("General" to 100_000L))
    private val plan = Plan(selling = true, now = early, later = listOf(door, regular))

    @Test
    fun `prices are those of the last change whose day has come`() {
        assertEquals(early, plan.at(999))
        assertEquals(regular.prices, plan.at(1_000))
        assertEquals(regular.prices, plan.at(1_999))
        assertEquals(door.prices, plan.at(5_000))
        assertEquals(regular, plan.next(0))
        assertEquals(door, plan.next(1_000))
        assertNull(plan.next(2_000))
    }

    @Test
    fun `a plan that does not run by itself changes only when its owner says`() {
        val held = plan.copy(byItself = false)
        assertEquals(early, held.at(5_000))
        assertEquals(held, held.settled(5_000))
    }

    @Test
    fun `a change that has come becomes the price now, and stays so`() {
        val settled = plan.settled(1_500)
        assertEquals(regular.prices, settled.now)
        assertEquals(listOf(door), settled.later)
        // Whatever the clock says afterwards, the price that was reached is kept.
        assertEquals(regular.prices, settled.at(0))
        assertEquals(plan, plan.settled(999))
    }

    @Test
    fun `the phone remembers a plan, and what was sold under it`() {
        val prices = Prices(context, evening)
        assertEquals(Plan(), prices.plan)
        assertEquals(Prices.TEST_MINT, prices.plan.mint)
        prices.plan = plan.copy(mint = "https://mint.other/", byItself = false, now = early + ("Crew" to 0L))
        prices.record(50_000)
        prices.record(75_000)

        val again = Prices(context, evening)
        // A kind that costs nothing is not for sale, and is not kept as if it were.
        assertEquals(plan.copy(mint = "https://mint.other/", byItself = false, later = listOf(regular, door)), again.plan)
        assertEquals(2 to 125_000L, again.sold)
        again.delete()
        assertEquals(Plan(), Prices(context, evening).plan)
    }

    private fun card(id: String, title: String, status: String = "owned") = JsonObject().apply {
        addProperty("id", id)
        addProperty("h", id.padEnd(64, '0'))
        addProperty("title", title)
        addProperty("status", status)
    }

    private fun wanted(plan: Plan, also: List<String> = emptyList()): Map<String, Long> =
        JsonParser.parseString(Sales.wanted(context, evening, plan, also)).asJsonArray
            .associate { it.asJsonObject.get("id").asString to it.asJsonObject.get("price").asLong }

    @Test
    fun `a few of each priced kind are put out, lowest numbers first, at today's price`() {
        val cards = JsonArray().apply {
            for (number in 9 downTo 1) add(card("a$number", "General · $number of 20"))
            add(card("b1", "VIP · 10 of 20"))
            add(card("c1", "Crew · 11 of 20"))
            add(card("d1", "General · 12 of 20", status = "sent"))
            add(card("e1", "A picture somebody sent us"))
        }
        Tickets(context, evening).save(cards)
        // One that was let in by hand is somebody's already.
        Admitted(context, evening).record("a2".padEnd(64, '0'), "door")
        val now = System.currentTimeMillis() / 1000
        val out = wanted(Plan(selling = true, now = early))

        assertEquals(setOf("a1", "a3", "a4", "a5", "a6", "b1"), out.keys)
        assertEquals(50_000L, out["a1"])
        assertEquals(150_000L, out["b1"])
        // A change that came yesterday prices everything that is out.
        val later = wanted(Plan(selling = true, now = early, later = listOf(Step(now - 86_400, regular.prices))))
        assertEquals(75_000L, later["a1"])
    }

    @Test
    fun `a collection that is not selling puts out only what a guest at the door is buying`() {
        Tickets(context, evening).save(JsonArray().apply {
            add(card("a1", "General · 1 of 2"))
            add(card("a2", "General · 2 of 2"))
        })
        assertTrue(wanted(Plan(selling = false, now = early)).isEmpty())
        assertEquals(mapOf("a2" to 50_000L), wanted(Plan(selling = false, now = early), also = listOf("a2")))
        // What has no price cannot be sold even at the door.
        assertTrue(wanted(Plan(selling = false, now = mapOf("VIP" to 9L)), also = listOf("a2")).isEmpty())
    }

    @Test
    fun `an NFT's kind is read from what a collection made here lists it as`() {
        assertEquals("General admission", Sales.kind("General admission · 12 of 50"))
        assertEquals("Friends · family", Sales.kind("Friends · family · 3 of 4"))
        assertNull(Sales.kind("013-general"))
        assertFalse(Sales.kind("VIP · 1 of 1").isNullOrEmpty())
    }

    @Test
    fun `a price in a currency is turned into sats, to three figures`() {
        // 121 dollars with a bitcoin at 95,000 dollars.
        assertEquals(127_000L, Prices.inSats(121, 95_000.0))
        assertEquals(1_050L, Prices.inSats(1, 95_238.0))
        assertEquals(21L, Prices.inSats(21, 100_000_000.0))
        val prices = Prices(context, evening)
        prices.plan = Plan(unit = "EUR", now = mapOf("General" to 121L))
        assertEquals("EUR", Prices(context, evening).plan.unit)
        assertEquals(Prices.SATS, Plan().unit)
    }

    @Test
    fun `what cannot be turned into sats is not put on sale`() {
        Tickets(context, evening).save(JsonArray().apply { add(card("a1", "General · 1 of 1")) })
        val plan = Plan(selling = true, unit = "USD", now = mapOf("General" to 121L))
        val priced = JsonParser.parseString(Sales.wanted(context, evening, plan, emptyList()) { Prices.inSats(it, 95_000.0) }).asJsonArray
        assertEquals(127_000L, priced.single().asJsonObject.get("price").asLong)
        assertEquals("[]", Sales.wanted(context, evening, plan, emptyList()) { null })
    }
}
