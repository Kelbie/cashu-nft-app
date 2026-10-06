package cash.nonfungible.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ConfigTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a new door has a default name and two public relays`() {
        val config = Config(context)
        assertEquals("Door", config.door)
        assertEquals(listOf("wss://relay.primal.net", "wss://nos.lol"), config.relays)
    }

    @Test
    fun `what the operator saves is what the next start reads`() {
        Config(context).save("  main entrance ", "")
        val config = Config(context)
        assertEquals("main entrance", config.door)
        assertEquals(2, config.relays.size)
    }

    @Test
    fun `a door saved without a name keeps the default`() {
        Config(context).save(" ", "")
        assertEquals("Door", Config(context).door)
    }

    @Test
    fun `a debug build listens where it is told to`() {
        val typed = " ws://localhost:7777,\nwss://relay.example  nonsense http://not.a.relay ws://"
        Config(context).save("Door", typed)
        assertEquals(listOf("ws://localhost:7777", "wss://relay.example"), Config(context).relays)
    }

    @Test
    fun `a door is never left without a relay`() {
        Config(context).save("Door", "nonsense")
        assertEquals(2, Config(context).relays.size)
    }

    @Test
    fun `collections are looked for on the site, and a debug build may be pointed elsewhere`() {
        assertEquals("https://nonfungible.cash", Config(context).site)
        Config(context).save("Door", "", debugSite = "http://localhost:8403/")
        assertEquals("http://localhost:8403", Config(context).site)
        // Only a site's address will do.
        Config(context).save("Door", "", debugSite = "http://10.0.0.7:8403")
        assertEquals("https://nonfungible.cash", Config(context).site)
    }

    @Test
    fun `an answer is heard and the code comes back by itself, unless the doorkeeper says not`() {
        assertTrue(Config(context).sound)
        assertTrue(Config(context).express)
        Config(context).save("Door", "", sound = false, express = false)
        assertFalse(Config(context).sound)
        assertFalse(Config(context).express)
    }

    @Test
    fun `what opened the app before anybody was named is kept for after, and a door that was open is remembered`() {
        assertNull(Config(context).pending)
        Config(context).pending = "https://nonfungible.cash/claim/abc#def"
        assertEquals("https://nonfungible.cash/claim/abc#def", Config(context).pending)
        Config(context).pending = null
        assertNull(Config(context).pending)
        assertNull(Config(context).doorOpen)
        Config(context).doorOpen = "https://nonfungible.cash/p/${"ab".repeat(32)}"
        assertEquals("https://nonfungible.cash/p/${"ab".repeat(32)}", Config(context).doorOpen)
    }
}
