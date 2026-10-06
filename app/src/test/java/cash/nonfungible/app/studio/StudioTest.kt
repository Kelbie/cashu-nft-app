package cash.nonfungible.app.studio

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cash.nonfungible.app.Tally
import cash.nonfungible.app.App
import cash.nonfungible.app.entry.Admitted
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Tickets
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The templates a phone has, and what it remembers of a collection it made with one. */
@RunWith(RobolectricTestRunner::class)
class StudioTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val templates = Templates(context)
    private val evening = checkNotNull(Collection.parse("https://mint.example/p/${"ab".repeat(32)}"))

    @Test
    fun `the app brings templates, each with a scene and the kinds it draws`() {
        val brought = templates.all
        assertEquals(listOf("Marble", "Poster", "Rings", "Stub", "Sunrise"), brought.map { it.name })
        for (template in brought) {
            assertFalse(template.own)
            assertTrue(template.ticket)
            assertTrue(template.kinds.isNotEmpty())
            assertNotNull(templates.open(template.id, "scene.js"))
        }
    }

    @Test
    fun `a template's page is served its own files and nothing above them`() {
        assertNotNull(templates.open("poster", "assets/Display.ttf"))
        assertNull(templates.open("poster", "assets/Missing.ttf"))
        assertNull(templates.open("poster", "../rings/scene.js"))
        assertNull(templates.open("../studio", "render.js"))
        assertNull(templates.open("no-such-template", "scene.js"))
    }

    @Test
    fun `a scene the holder brings becomes a template of their own, until they remove it`() {
        val mine = templates.add("Mine", "export async function renderTicketArt() {}")
        assertTrue(mine.own)
        assertTrue(Templates.ID.matches(mine.id))
        assertEquals("Mine", templates.all.last().name)
        val scene = checkNotNull(templates.open(mine.id, "scene.js")).reader().use { it.readText() }
        assertEquals("export async function renderTicketArt() {}", scene)
        templates.remove(mine)
        assertNull(templates.named(mine.id))
        // The app's own stay.
        templates.remove(checkNotNull(templates.named("poster")))
        assertNotNull(templates.named("poster"))
    }

    @Test
    fun `a scene says what its collection is made of, and is offered as that`() {
        val mine = templates.add("Critters", "export async function renderTicketArt() {}")
        assertTrue(mine.ticket)
        assertEquals(listOf(Kind("General admission", JsonObject())), mine.kinds)

        val fire = JsonObject().apply { addProperty("element", "fire") }
        val said = JsonParser.parseString(
            """{"ticket":false,"edition":"Series one","name":"Not its name",
               "kinds":[{"label":"Embit","count":3,"options":{"element":"fire"}},{"label":"Dewlet","count":900},{"count":2}]}""",
        ).asJsonObject
        val told = templates.describe(mine, said)
        assertFalse(told.ticket)
        assertEquals("Series one", told.edition)
        // The name is the one its holder gave it; a kind with no label is none, and no count is past the most.
        assertEquals("Critters", told.name)
        assertEquals(listOf(Kind("Embit", fire, 3), Kind("Dewlet", JsonObject(), Templates.MOST)), told.kinds)
        assertEquals(told, templates.named(mine.id))

        // One that names no kind changes nothing, and the app's own are never rewritten.
        assertEquals(told, templates.describe(told, JsonObject().apply { add("kinds", JsonArray()) }))
        assertEquals(told, templates.named(mine.id))
        val poster = checkNotNull(templates.named("poster"))
        assertEquals(poster, templates.describe(poster, said))
        assertTrue(poster.ticket)
        templates.remove(told)
    }

    @Test
    fun `a collection made here knows each NFT's place among its own kind`() {
        val made = Made(context, evening)
        assertEquals(0, made.issuedOf("Embit"))
        made.count("Embit")
        made.count("Dewlet")
        made.count("Embit")
        val again = Made(context, evening)
        assertEquals(2, again.issuedOf("Embit"))
        assertEquals(1, again.issuedOf("Dewlet"))
        assertEquals(0, again.issuedOf("Pebbit"))
        again.delete()
        assertEquals(0, Made(context, evening).issuedOf("Embit"))
    }

    @Test
    fun `a collection made here remembers its look and how far it has numbered`() {
        val made = Made(context, evening)
        assertNull(made.template)
        assertEquals(0, made.issued)
        val poster = checkNotNull(templates.named("poster"))
        val kinds = listOf(Kind("General admission", poster.kinds[0].options), Kind("Friends", JsonObject()))
        made.save(poster, kinds)
        made.issued = 12

        val again = Made(context, evening)
        assertEquals("poster", again.template)
        assertEquals(kinds, again.kinds)
        assertEquals(12, again.issued)
        again.delete()
        assertNull(Made(context, evening).template)
        assertEquals(0, Made(context, evening).issued)
    }

    @Test
    fun `a collection made here knows how many of its NFTs are still its owner's`() {
        val app = context as App
        Tickets(context, evening).save(JsonArray().apply {
            for ((id, status) in listOf("a" to "owned", "b" to "owned", "c" to "sent")) {
                add(JsonObject().apply {
                    addProperty("id", id)
                    addProperty("h", id.repeat(64))
                    addProperty("title", "General · 1 of 3")
                    addProperty("status", status)
                })
            }
        })
        Admitted(context, evening).record("c".repeat(64), "door")
        assertEquals(Tally(all = 3, admitted = 1, held = 2), app.tally(evening))
        // Only a collection the account made is its to hand out from: one it keeps a door for is not.
        assertFalse(app.owns(evening))
        app.collections.add(evening, "An Evening")
        assertFalse(app.owns(evening))
        app.collections.add(evening, "An Evening", mine = true)
        assertTrue(app.owns(evening))
        // A list kept before accounts did not say whose a collection was: it was its maker's phone's.
        app.getSharedPreferences("collections", android.content.Context.MODE_PRIVATE).edit()
            .putString("list", """[{"url":"${evening.url}","name":"An Evening"}]""").commit()
        assertFalse(app.owns(evening))
        Made(context, evening).save(checkNotNull(templates.named("poster")), emptyList())
        assertTrue(app.owns(evening))
    }
}
