package cash.nonfungible.app.guest

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import cash.nonfungible.app.Config
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Entry
import cash.nonfungible.app.entry.Vectors
import cash.nonfungible.app.Account
import cash.nonfungible.app.App
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GuestTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config get() = Config(context)
    private val id = "0123456789abcdef0123456789abcdef"
    private val key = "A".repeat(43)
    private val pubkey = "ab".repeat(32)

    @Test
    fun `a door's request is a door, whatever is around it`() {
        assertEquals(Scanned.Door("creqAabc"), Scanned.read("  creqAabc\n", config))
    }

    @Test
    fun `a link that gives a ticket is a gift only with its key`() {
        val link = "https://nonfungible.cash/claim/$id#$key"
        assertEquals(Scanned.Gift(link), Scanned.read(link, config))
        // Without the key after the '#', nothing can be taken: it is not read as a gift.
        assertNull(Scanned.read("https://nonfungible.cash/claim/$id", config))
        assertNull(Scanned.read("https://nonfungible.cash/claim/$id#short", config))
    }

    @Test
    fun `a link to a sale says what is sold, and where it is paid if the seller said`() {
        assertEquals(Scanned.Sale(id, null), Scanned.read("https://nonfungible.cash/market/$id", config))
        assertEquals(
            Scanned.Sale(id, "https://mint.example"),
            Scanned.read("https://nonfungible.cash/market/$id?offer=1&mint=https%3A%2F%2Fmint.example", config),
        )
    }

    @Test
    fun `a collection's page is an event`() {
        val read = Scanned.read("https://nonfungible.cash/p/$pubkey?from=poster#top", config)
        assertTrue(read is Scanned.Event)
        assertEquals(pubkey, (read as Scanned.Event).collection.pubkey)
    }

    @Test
    fun `nothing from another site is read, however alike it looks`() {
        assertNull(Scanned.read("https://nonfungible.cash.example/claim/$id#$key", config))
        assertNull(Scanned.read("https://user@nonfungible.cash/claim/$id#$key", config))
        assertNull(Scanned.read("https://example.com/market/$id", config))
        assertNull(Scanned.read("http://nonfungible.cash/market/$id", config))
        assertNull(Scanned.read("lnbc1notours", config))
        assertNull(Scanned.read("", config))
    }

    @Test
    fun `a link that gives an NFT is read as the site writes it, whatever was added on the way`() {
        val link = "https://nonfungible.cash/claim/$id#$key"
        assertEquals(Scanned.Gift(link), Scanned.read("https://nonfungible.cash/claim/$id?utm=x#$key", config))
    }

    @Test
    fun `a door asks somebody who holds nothing, and they are told so without a collection being made for them`() {
        val guest = Guest(context as App, Account(Account.FIRST, ""))
        val ours = Entry.request(checkNotNull(Collection.parse("https://nonfungible.cash/p/$pubkey")), "main entrance", Vectors.nprofile)
        val door = checkNotNull(guest.atDoor(ours.encoded))
        assertEquals("main entrance", door.door)
        assertTrue(door.mine.isEmpty())
        assertFalse(door.sure)
        assertNull(guest.me)
        // What is not a door's request, or is a request for another site's NFTs, is nothing to a guest.
        assertNull(guest.atDoor("lnbc1notours"))
        assertNull(guest.atDoor(Vectors.encoded))
    }

    @Test
    fun `an account starts with nothing, is called what its owner said, and remembers what is on sale`() {
        val app = context as App
        val account = app.accounts.add("Satoshi")
        val guest = Guest(app, account)
        assertNull(guest.me)
        assertEquals("Satoshi", guest.name)
        app.accounts.rename(account, "Hal")
        assertEquals("Hal", guest.name)
        assertTrue(guest.asks.isEmpty())
        // With nothing on sale and nothing on its way, a phone at a door has no reason to ask the site.
        assertFalse(guest.expecting)
        assertTrue(guest.held.isEmpty())
        assertEquals(0, guest.had)
        assertNull(guest.purse)
    }

    @Test
    fun `the wallet shows the mint it is paid at, and showing another moves where it is paid unless something is for sale`() {
        val app = context as App
        val guest = Guest(app, app.accounts.add("Satoshi"))
        assertEquals(guest.mint, guest.viewing)
        guest.view("https://mint.minibits.cash/Bitcoin")
        assertEquals("https://mint.minibits.cash/Bitcoin", guest.viewing)
        assertEquals("https://mint.minibits.cash/Bitcoin", guest.mint)
    }

    @Test
    fun `the first account keeps what the phone kept before it had accounts, and each later one keeps its own`() {
        val app = context as App
        assertNull(app.accounts.active)
        val first = app.accounts.add("Ada")
        assertEquals(Account.FIRST, first.id)
        assertEquals("guest", first.store("guest"))
        assertEquals("collections", first.store("collections"))
        val second = app.accounts.add("Door crew")
        assertEquals(second, app.accounts.active)
        assertEquals("guest.${second.id}", second.store("guest"))
        assertEquals(64, second.mark.length)
        app.accounts.use(first)
        assertEquals(first, app.accounts.active)
        app.accounts.remove(first)
        assertEquals(listOf(second), app.accounts.all)
        assertEquals(second, app.accounts.active)
        // A name is something, in one line, of no more than forty characters.
        assertNull(Account.named("   "))
        assertEquals("Ada Lovelace", Account.named("  Ada   Lovelace "))
        assertEquals(40, Account.named("x".repeat(60))?.length)
    }
}
