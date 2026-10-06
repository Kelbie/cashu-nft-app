package cash.nonfungible.app.entry

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.bouncycastle.util.encoders.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.concurrent.TimeUnit

/** The door's decision against a mint that answers as the site does. */
@RunWith(RobolectricTestRunner::class)
class DoorTest {

    private val server = MockWebServer()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val admitted get() = Admitted(context, collection)
    private val h = Vectors.assetHash

    // What the mint and the site answer; a test changes the one answer it is about.
    private var verify = MockResponse().setBody("""{"valid":true,"spent":false}""")
    private var asset = MockResponse().setBody("""{"asset_hash":"$h","status":"active"}""")
    private var profile = MockResponse().setBody(
        """{"name":"An Evening","cards":[
            {"h":"${"00".repeat(32)}","title":"Another","showing":"pshow100","status":"active"},
            {"h":"$h","title":"Ticket 2","status":"sent"}]}"""
    )

    // The page of a collection that holds the ticket and says so under its own key.
    private fun page(card: JsonObject = Vectors.holderCard, name: String = "Kelbie") =
        MockResponse().setBody("""{"name":"$name","avatar":7,"cards":[$card]}""")
    private var holder = page()

    private lateinit var collection: Collection
    private lateinit var asked: Asked
    private val answer = checkNotNull(Entry.answer(Vectors.answer))

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                "/v1/nft/verify" -> verify
                "/api/profiles/${Vectors.collection.pubkey}" -> profile
                "/api/profiles/${Vectors.holder}" -> holder
                else -> if (request.path.orEmpty().startsWith("/v1/nft/asset/")) asset
                else MockResponse().setResponseCode(404)
            }
        }
        server.start()
        // The request is the reference's; only where the door looks for its site is local.
        collection = Vectors.collection.copy(site = server.url("/").toString().trimEnd('/'))
        asked = Vectors.asked(collection)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // A door that does not make a test wait between its attempts.
    private val door by lazy { Door(context, retryMs = 1) }

    private val let = Decision(h, Verdict.Admitted)

    /** What the door decides about an answer whose code is still there to be spent. */
    private fun Door.decided(answer: Answer, asked: Asked): Arrival = checkNotNull(decide(answer, asked))

    /** What the door decides about an answer that shows one NFT. */
    private fun decide(answer: Answer = this.answer, asked: Asked = this.asked): Decision =
        door.decided(answer, asked).decisions.single()

    private fun assertRefused(reason: Refusal, decision: Decision = decide()) {
        assertEquals(Decision(h, Verdict.Refused(reason)), decision)
        assertEquals("a refusal must record nothing", 0, admitted.count)
    }

    /** Who the door takes the holder to be when the answer is signed with the holder's key. */
    private fun named(): Holder? =
        door.decided(Answer(answer.id, answer.showings, Vectors.holder), asked).holder

    @Test
    fun `a holder of one of the collection's tickets is admitted and recorded`() {
        assertEquals(let, decide())
        assertEquals(1, admitted.count)
    }

    @Test
    fun `the mint is asked to verify the presentation as a showing of the request`() {
        decide()
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v1/nft/verify", request.path)
        val body = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals(Vectors.presentationHex, body.get("presentation").asString)
        assertEquals(Vectors.bindingHex, body.get("binding").asString)
        assertEquals("/v1/nft/asset/$h", server.takeRequest().path)
        assertEquals("/api/profiles/${collection.pubkey}", server.takeRequest().path)
    }

    @Test
    fun `a ticket is admitted once`() {
        decide()
        val again = decide()
        assertEquals(Vectors.door, (again.verdict as Verdict.AlreadyIn).earlier.door)
        assertEquals(h, again.h)
        assertEquals(1, admitted.count)
    }

    @Test
    fun `an admission taken back lets the ticket in again`() {
        decide()
        admitted.forget(h)
        assertEquals(let, decide())
    }

    @Test
    fun `a collection's list is read again only for an asset not seen in it`() {
        decide()
        decide()
        // The second showing asked the mint about the proof and the asset, and nothing else.
        assertEquals(5, server.requestCount)
        profile = MockResponse().setBody("""{"name":"An Evening","cards":[]}""")
        assertTrue(decide().verdict is Verdict.AlreadyIn)
    }

    @Test
    fun `reading a collection keeps its tickets, one for each asset, sold on or not`() {
        profile = MockResponse().setBody(
            """{"name":"An Evening","cards":[
                {"h":"$h","title":"Ticket 2","status":"owned","created":20},
                {"h":"$h","title":"Ticket 2","status":"sent","created":10},
                {"h":"${"00".repeat(32)}","title":"Ticket 1","status":"sent","created":5},
                {"h":"not a hash","title":"Junk"}]}"""
        )
        assertEquals("An Evening", door.sync(collection))
        val kept = Tickets(context, collection).all
        val sold = Ticket("00".repeat(32), "Ticket 1", 5, sent = true)
        assertEquals(listOf(Ticket(h, "Ticket 2", 20), sold), kept)
    }

    @Test
    fun `a ticket minted after the list was kept is found by reading the page again`() {
        profile = MockResponse().setBody("""{"name":"An Evening","cards":[]}""")
        door.sync(collection)
        assertRefused(Refusal.NOT_ISSUED)
        profile = MockResponse().setBody("""{"name":"An Evening","cards":[{"h":"$h"}]}""")
        assertEquals(let, decide())
        assertEquals(listOf(h), Tickets(context, collection).all.map(Ticket::h))
    }

    @Test
    fun `a page that cannot be read leaves the kept list as it was`() {
        door.sync(collection)
        profile = MockResponse().setResponseCode(500)
        assertNull(door.sync(collection))
        assertEquals(2, Tickets(context, collection).all.size)
    }

    @Test
    fun `a site that is busy is asked again`() {
        val busy = mutableListOf(429, 503)
        val answers = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                busy.removeFirstOrNull()?.let(MockResponse()::setResponseCode)
                    ?: answers.dispatch(request)
        }
        assertEquals(let, decide())
    }

    @Test
    fun `a proof the mint cannot read is not valid, and is not asked about again`() {
        verify = MockResponse().setResponseCode(400)
        assertRefused(Refusal.NOT_VALID)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `a ticket the collection has no public claim for is still admitted`() {
        profile = MockResponse().setBody("""{"name":"An Evening","cards":[{"h":"$h"}]}""")
        assertEquals(let, decide())
    }

    @Test
    fun `an answer to another request is refused before the mint is asked`() {
        assertRefused(Refusal.NOT_VALID, decide(Answer("ff".repeat(16), answer.showings)))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a proof bound to another request is refused before the mint is asked`() {
        // The same id, as an attacker replaying an old proof would send it.
        val other = Entry.request(collection, "side door", Vectors.nprofile, Vectors.id)
        assertRefused(Refusal.NOT_VALID, decide(asked = other))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `a proof the mint does not verify is refused`() {
        verify = MockResponse().setBody("""{"valid":false,"spent":false}""")
        assertRefused(Refusal.NOT_VALID)
    }

    @Test
    fun `a credential that was spent is refused`() {
        verify = MockResponse().setBody("""{"valid":true,"spent":true}""")
        assertRefused(Refusal.NOT_OWNER)
    }

    @Test
    fun `a ticket that is not active is refused`() {
        for (status in listOf("burned", "unknown")) {
            asset = MockResponse().setBody("""{"asset_hash":"$h","status":"$status"}""")
            assertRefused(Refusal.NOT_OWNER)
        }
    }

    @Test
    fun `an NFT the collection never held is refused`() {
        profile = MockResponse().setBody("""{"name":"An Evening","cards":[{"h":"${"00".repeat(32)}"}]}""")
        assertRefused(Refusal.NOT_ISSUED)
        profile = MockResponse().setBody("""{"name":"An Evening","cards":[]}""")
        assertRefused(Refusal.NOT_ISSUED)
    }

    @Test
    fun `an answer the mint does not give plainly refuses`() {
        val unexpected = listOf(
            MockResponse().setResponseCode(500).setBody("""{"valid":true,"spent":false}"""),
            MockResponse().setResponseCode(404),
            MockResponse().setBody(""),
            MockResponse().setBody("not json"),
            MockResponse().setBody("[]"),
            MockResponse().setBody("{}"),
            MockResponse().setBody("""{"valid":true}"""),
            MockResponse().setBody("""{"valid":"true","spent":"false"}"""),
            MockResponse().setBody("""{"valid":1,"spent":0}"""),
            MockResponse().setBody("""{"valid":true,"spent":null}"""),
        )
        for (response in unexpected) {
            verify = response
            assertRefused(Refusal.NOT_CHECKED)
        }
    }

    @Test
    fun `an asset status the mint does not give plainly refuses`() {
        val unexpected = listOf(
            MockResponse().setResponseCode(400).setBody("""{"status":"active"}"""),
            MockResponse().setBody("{}"),
            MockResponse().setBody("""{"status":true}"""),
            MockResponse().setBody("""{"status":null}"""),
        )
        for (response in unexpected) {
            asset = response
            assertRefused(Refusal.NOT_CHECKED)
        }
    }

    @Test
    fun `a collection the site does not list plainly refuses`() {
        val unexpected = listOf(
            MockResponse().setResponseCode(404).setBody("""{"detail":"not created"}"""),
            MockResponse().setBody("""{"name":"An Evening"}"""),
            MockResponse().setBody("""{"cards":{"h":"$h"}}"""),
            MockResponse().setBody("""{"cards":"$h"}"""),
        )
        for (response in unexpected) {
            profile = response
            assertRefused(Refusal.NOT_CHECKED)
        }
    }

    @Test
    fun `a mint that cannot be reached refuses`() {
        server.shutdown()
        assertRefused(Refusal.NOT_CHECKED)
    }

    @Test
    fun `a mint that does not answer in time refuses`() {
        val impatient = Door(context, patienceMs = 500)
        // Each answer is the one that admits, late.
        for (slow in listOf(verify, asset, profile)) {
            slow.setHeadersDelay(3, TimeUnit.SECONDS)
            assertRefused(Refusal.NOT_CHECKED, impatient.decided(answer, asked).decisions.single())
            slow.setHeadersDelay(0, TimeUnit.SECONDS)
        }
        assertEquals(let, impatient.decided(answer, asked).decisions.single())
    }

    @Test
    fun `a group is decided one NFT at a time, in the order shown`() {
        val group = checkNotNull(Entry.answer(Vectors.group))
        val arrival = door.decided(group, asked)
        // The collection lists the first and never held the second.
        val stranger = Decision(Vectors.plainHash, Verdict.Refused(Refusal.NOT_ISSUED))
        assertEquals(listOf(let, stranger), arrival.decisions)
        assertEquals(collection.url, arrival.collection)
        assertEquals(1, admitted.count)
        assertFalse(arrival.fake)
    }

    @Test
    fun `an answer with no real proof in it is a fake, and one with any is not`() {
        verify = MockResponse().setBody("""{"valid":false,"spent":false}""")
        assertTrue(door.decided(answer, asked).fake)
        verify = MockResponse().setBody("""{"valid":true,"spent":true}""")
        assertFalse(door.decided(answer, asked).fake)
    }

    @Test
    fun `nothing is spent or recorded for an answer the mint disowns`() {
        verify = MockResponse().setBody("""{"valid":false,"spent":false}""")
        var asked = 0
        val arrival = door.decide(answer, this.asked) { asked++ > 0 }
        // Anybody who can see the door's code can send it junk. Junk never gets as far as the code.
        assertEquals(0, asked)
        assertTrue(checkNotNull(arrival).fake)
        assertEquals(0, admitted.count)
    }

    @Test
    fun `an answer whose code was spent while the mint was asked lets nobody in`() {
        // Two answers to one code are borne out together. The second finds the code gone.
        assertNull(door.decide(answer, asked) { false })
        assertEquals(0, admitted.count)
        assertEquals(let, door.decided(answer, asked).decisions.single())
    }

    @Test
    fun `a holder who signs with their collection's key is named by its page`() {
        assertEquals(Holder(Vectors.holder, "Kelbie"), named())
        assertNull("an answer signed with a passing key names nobody", door.decided(answer, asked).holder)
    }

    @Test
    fun `a page that does not claim the credential shown names nobody`() {
        val card = Vectors.holderCard
        fun with(member: String, value: String) = card.deepCopy().apply { addProperty(member, value) }
        val pages = listOf(
            MockResponse().setResponseCode(404),
            MockResponse().setBody("""{"name":"Kelbie","cards":[]}"""),
            // Passed on since: the page no longer holds it.
            page(with("status", "sent")),
            // Another key's signature, and no signature at all.
            page(with("signature", Vectors.signedByAnother)),
            page(with("signature", "")),
            // A claim of the same picture under another credential: an earlier owner's.
            page(with("showing", Vectors.claimOfAnotherCredential)),
            // Somebody else's claim, copied onto this page.
            page(with("showing", card.get("showing").asString.replace(Hex.toHexString(Vectors.holder.toByteArray()), Hex.toHexString("cd".repeat(32).toByteArray())))),
        )
        for (page in pages) {
            holder = page
            assertNull(named())
        }
    }

    @Test
    fun `a name is not earned by a proof the mint turned away`() {
        // A real ticket under a credential no page claims, and with it a made-up proof of
        // another asset that carries the nullifier a named collection's page publishes.
        val shown = answer.showings.single()
        val ticket = shown.presentation.copyOf().also { it[220] = (it[220] + 1).toByte() }
        val madeUp = shown.presentation.copyOf().also { it[40] = (it[40] + 1).toByte() }
        val both = listOf(Showing(shown.context, ticket), Showing(shown.context, madeUp))
        val answers = server.dispatcher
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path != "/v1/nft/verify") return answers.dispatch(request)
                val proof = JsonParser.parseString(request.body.readUtf8()).asJsonObject
                val real = proof.get("presentation").asString == Hex.toHexString(ticket)
                return MockResponse().setBody("""{"valid":$real,"spent":false}""")
            }
        }

        val arrival = door.decided(Answer(answer.id, both, Vectors.holder), asked)

        assertEquals(let, arrival.decisions[0])
        assertEquals(Verdict.Refused(Refusal.NOT_VALID), arrival.decisions[1].verdict)
        // The page matches only the proof that was turned away.
        assertNull(arrival.holder)
    }

    @Test
    fun `a page that says odd things about itself costs nobody their admission`() {
        holder = MockResponse().setBody(
            """{"name":"Kelbie","avatar":1e2147483648,"cards":[${Vectors.holderCard}]}"""
        )
        val arrival = door.decided(Answer(answer.id, answer.showings, Vectors.holder), asked)
        assertEquals(listOf(let), arrival.decisions)
        assertEquals(Holder(Vectors.holder, "Kelbie"), arrival.holder)
    }

    @Test
    fun `nobody is named for an answer that showed nothing real`() {
        verify = MockResponse().setBody("""{"valid":false,"spent":false}""")
        assertNull(named())
    }

    @Test
    fun `the collection's name is read from its page on the site`() {
        assertEquals("An Evening", Door(context, retryMs = 1).sync(collection))
    }

    @Test
    fun `an address with no collection has no name`() {
        profile = MockResponse().setResponseCode(404)
        assertNull(Door(context, retryMs = 1).sync(collection))
        profile = MockResponse().setBody("""{"cards":[]}""")
        assertNull(Door(context, retryMs = 1).sync(collection))
        server.shutdown()
        assertNull(Door(context, retryMs = 1).sync(collection))
    }
}
