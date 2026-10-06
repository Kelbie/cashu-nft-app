package cash.nonfungible.app.entry

import android.util.Base64
import com.google.gson.JsonParser
import com.upokecenter.cbor.CBORObject
import org.bouncycastle.util.encoders.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class EntryTest {

    @Test
    fun `request is the reference's request, byte for byte`() {
        assertEquals(Vectors.encoded, Vectors.asked().encoded)
    }

    @Test
    fun `request decodes to the fields a wallet reads`() {
        val encoded = Vectors.asked().encoded
        assertTrue(encoded.startsWith("creqA"))
        val body = CBORObject.DecodeFromBytes(Base64.decode(encoded.substring(5), Base64.URL_SAFE))
        assertEquals(Vectors.fields, JsonParser.parseString(body.ToJSONString()))
    }

    @Test
    fun `request remembers what the door needs to check its answer`() {
        val asked = Vectors.asked()
        assertEquals(Vectors.id, asked.id)
        assertEquals(Vectors.collection, asked.collection)
        assertEquals(Vectors.door, asked.door)
    }

    @Test
    fun `every request has a new id of sixteen random bytes`() {
        val first = Entry.request(Vectors.collection, Vectors.door, Vectors.nprofile)
        val second = Entry.request(Vectors.collection, Vectors.door, Vectors.nprofile)
        assertTrue(first.id.matches(Regex("[0-9a-f]{32}")))
        assertNotEquals(first.id, second.id)
        assertNotEquals(first.encoded, second.encoded)
    }

    @Test
    fun `context is the entry domain and the request as shown`() {
        val context = Entry.context(Vectors.encoded)
        assertEquals("Cashu_NFT_Ticket_Entry_v1\n" + Vectors.encoded, String(context))
        assertEquals(Vectors.contextHex, Hex.toHexString(context))
    }

    @Test
    fun `binding is the showing domain, the context's length and the context`() {
        val binding = Entry.binding(Hex.decode(Vectors.contextHex))
        assertEquals(Vectors.bindingHex, Hex.toHexString(binding))
    }

    @Test
    fun `answer carries the request's id and one showing of its context`() {
        val answer = checkNotNull(Entry.answer(Vectors.answer))
        assertEquals(Vectors.id, answer.id)
        val showing = answer.showings.single()
        assertEquals(Vectors.contextHex, Hex.toHexString(showing.context))
        assertEquals(Vectors.presentationHex, Hex.toHexString(showing.presentation))
        assertEquals(Vectors.assetHash, showing.assetHash)
        assertEquals(Vectors.nullifierHex, showing.nullifier)
        assertNull(answer.sender)
    }

    @Test
    fun `a request is answered by its id and a proof of exactly its text`() {
        val answer = checkNotNull(Entry.answer(Vectors.answer))
        val asked = Vectors.asked()
        assertTrue(asked.isAnsweredBy(answer))
        assertFalse(asked.isAnsweredBy(Answer("ff".repeat(16), answer.showings)))
        assertFalse(asked.copy(id = "ff".repeat(16)).isAnsweredBy(answer))
        assertFalse(asked.copy(encoded = asked.encoded + "A").isAnsweredBy(answer))
        // Another request with the same id, as someone replaying an old proof would need.
        val other = Entry.request(Vectors.collection, "side door", Vectors.nprofile, Vectors.id)
        assertFalse(other.isAnsweredBy(answer))
    }

    @Test
    fun `an answer names its holder only when it says so and the key that signed it is that one`() {
        val named = JsonParser.parseString(Vectors.answer).asJsonObject.apply { addProperty("sender", "ab".repeat(32)) }.toString()
        assertEquals("ab".repeat(32), Entry.answer(named, "ab".repeat(32))?.sender)
        // Signed by a key it does not name, or naming nobody: the door has no page to look for.
        assertNull(checkNotNull(Entry.answer(named, "cd".repeat(32))).sender)
        assertNull(checkNotNull(Entry.answer(named)).sender)
        assertNull(checkNotNull(Entry.answer(Vectors.answer, "ab".repeat(32))).sender)
    }

    @Test
    fun `a holder's phone reads what a door asks with nobody to ask`() {
        val read = checkNotNull(Entry.asked(Vectors.encoded))
        assertEquals(Vectors.asked(), read)
        assertEquals(Vectors.door, read.door)
        assertEquals(Vectors.collection, read.collection)
        // Not a request, a request for money, and one cut short.
        assertNull(Entry.asked("lnbc1notours"))
        assertNull(Entry.asked("creqA"))
        assertNull(Entry.asked(Vectors.encoded.dropLast(40)))
        val money = CBORObject.NewOrderedMap().Add("i", Vectors.id).Add("u", "sat")
            .Add("m", CBORObject.NewArray().Add(Vectors.collection.url))
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        assertNull(Entry.asked("creqA" + Base64.encodeToString(money.EncodeToBytes(), flags)))
    }

    @Test
    fun `an answer handed to the door is the same answer, without the request it need not send back`() {
        val sent = checkNotNull(Entry.answer(Vectors.answer))
        val text = "TICKET:${Vectors.id}:${Vectors.presentationHex}".uppercase()
        val direct = checkNotNull(Entry.direct(text))
        assertEquals(Vectors.id, direct.id)
        assertNull(direct.sender)
        val answer = direct.to(Vectors.asked())
        assertTrue(Vectors.asked().isAnsweredBy(answer))
        assertEquals(sent.showings.single().assetHash, answer.showings.single().assetHash)
        assertEquals(Vectors.nullifierHex, answer.showings.single().nullifier)
        // What the mint is asked is exactly what it is asked for an answer sent over the network.
        assertEquals(Vectors.bindingHex, Hex.toHexString(Entry.binding(answer.showings.single().context)))
        // A holder who wants to be greeted adds their collection, and case is nothing.
        assertEquals("ab".repeat(32), Entry.direct("$text:${"AB".repeat(32)}")?.sender)
        assertEquals(Vectors.id, Entry.direct(text.lowercase())?.id)
    }

    @Test
    fun `what is not an answer handed to the door is not read as one`() {
        val one = Vectors.presentationHex
        assertNull(Entry.direct(""))
        assertNull(Entry.direct("TICKET:${Vectors.id}"))
        assertNull(Entry.direct("TICKET:${Vectors.id}:${one.dropLast(2)}"))
        assertNull(Entry.direct("TICKET:${Vectors.id}:${one}00"))
        assertNull(Entry.direct("TICKET:short:$one"))
        assertNull(Entry.direct("PSHOW:${Vectors.id}:$one"))
        // The same NFT twice, and more than ten.
        assertNull(Entry.direct("TICKET:${Vectors.id}:$one:$one"))
        val many = (0..10).joinToString(":") { one.replaceRange(70, 72, "%02x".format(it)) }
        assertNull(Entry.direct("TICKET:${Vectors.id}:$many"))
        assertEquals(10, Entry.direct("TICKET:${Vectors.id}:${many.substringBeforeLast(':')}")?.presentations?.size)
        assertNull(Entry.direct(Vectors.encoded))
    }

    @Test
    fun `an answer may show a group's NFTs, each once`() {
        val group = checkNotNull(Entry.answer(Vectors.group, "ab".repeat(32)))
        assertEquals(listOf(Vectors.assetHash, Vectors.plainHash), group.showings.map { it.assetHash })
        assertTrue(Vectors.asked().isAnsweredBy(group))
        // One of the group answering another request spoils the whole answer.
        val other = Entry.request(Vectors.collection, "side door", Vectors.nprofile, Vectors.id)
        val stray = Showing(Entry.context(other.encoded), group.showings[1].presentation)
        assertFalse(Vectors.asked().isAnsweredBy(Answer(Vectors.id, listOf(group.showings[0], stray))))
    }

    @Test
    fun `an answer shows no more than ten NFTs`() {
        val proof = Vectors.proof
        // Each a different asset: the hash is the 32 bytes after the keyset in the presentation.
        fun other(n: Int): String {
            val at = proof.length - 2 * 321 + 2 * 33
            return proof.substring(0, at) + "%064x".format(n) + proof.substring(at + 64)
        }
        fun answer(count: Int) = Entry.answer(
            """{"id":"a","proofs":[${(1..count).joinToString(",") { "\"${other(it)}\"" }}]}"""
        )
        assertEquals(Entry.MOST, answer(Entry.MOST)?.showings?.size)
        assertNull(answer(Entry.MOST + 1))
    }

    @Test
    fun `a collection written two ways is one collection, and no two share what is kept`() {
        val key = "ab".repeat(32)
        val plain = checkNotNull(Collection.parse("https://mint.example/p/$key"))
        // A port that goes without saying is not part of the address.
        assertEquals(plain, Collection.parse("https://mint.example:443/p/$key"))
        assertEquals(plain, Collection.parse("https://mint.example:0443/p/$key"))
        assertEquals("http://localhost", Collection.parse("http://localhost:80/p/$key")?.site)
        val other = checkNotNull(Collection.parse("https://mint.example:8443/p/$key"))
        assertEquals("https://mint.example:8443/p/$key", other.url)
        assertNotEquals(plain.key, other.key)
        // Names that differ only in their punctuation are different sites.
        val dashed = checkNotNull(Collection.parse("https://a-b.example/p/$key"))
        val dotted = checkNotNull(Collection.parse("https://a.b.example/p/$key"))
        assertNotEquals(dashed.key, dotted.key)
        assertTrue(Regex("[0-9a-f]{32}").matches(plain.key))
    }

    @Test
    fun `text that is not an answer is not read as one`() {
        val proof = Vectors.proof
        val notAnswers = listOf(
            "",
            "hello",
            "[]",
            "{",
            """{"proofs":["$proof"]}""",
            """{"id":7,"proofs":["$proof"]}""",
            """{"id":"a"}""",
            """{"id":"a","proofs":"$proof"}""",
            """{"id":"a","proofs":[]}""",
            """{"id":"a","proofs":[7]}""",
            """{"id":"a","proofs":[["$proof"]]}""",
            """{"id":"a","proofs":["$proof","$proof"]}""",
            """{"id":"a","proofs":{"0":"$proof"}}""",
            """{"id":null,"proofs":["$proof"]}""",
            """"$proof"""",
            "null",
            """{"id":"a","proofs":["cashuB${proof.substring(6)}"]}""",
            """{"id":"a","proofs":["pshow1zz"]}""",
            """{"id":"a","proofs":["pshow1"]}""",
            """{"id":"a","proofs":["${proof}00"]}""",
            """{"id":"a","proofs":["${proof.dropLast(2)}"]}""",
            """{"id":"a","proofs":["pshow1ffff${proof.substring(10)}"]}""",
        )
        for (text in notAnswers) assertNull(text, Entry.answer(text))
    }

    @Test
    fun `collection is the address of its page on a site`() {
        val pubkey = "ab".repeat(32)
        val page = checkNotNull(Collection.parse("https://mint.example/p/$pubkey"))
        assertEquals("https://mint.example/p/$pubkey", page.url)
        assertEquals("https://mint.example", page.site)
        assertEquals(pubkey, page.pubkey)
        assertEquals("http://localhost:3338", Collection.parse("http://localhost:3338/p/$pubkey")?.site)
        assertEquals("http://127.0.0.1:5181", Collection.parse("http://127.0.0.1:5181/p/$pubkey")?.site)
        // Plain http reaches only this machine.
        assertNull(Collection.parse("http://mint.example/p/$pubkey"))
        assertNull(Collection.parse("http://10.0.2.2/p/$pubkey"))
    }

    @Test
    fun `text that is not exactly a collection page's address is not read as one`() {
        val pubkey = "ab".repeat(32)
        val notPages = listOf(
            "",
            "mint.example/p/$pubkey",
            "ftp://mint.example/p/$pubkey",
            " https://mint.example/p/$pubkey",
            "https://mint.example/p/$pubkey\n",
            "https://mint.example/p/$pubkey/",
            "https://mint.example/p/$pubkey?nft=${"f".repeat(32)}",
            "https://mint.example/p/$pubkey#top",
            "https://mint.example/p/${pubkey.dropLast(1)}",
            "https://mint.example/p/${pubkey.uppercase()}",
            "https://Mint.example/p/$pubkey",
            "https://user@mint.example/p/$pubkey",
            "https://mint.example/site/p/$pubkey",
            "https://mint.example/market/$pubkey",
            "https://mint.example:port/p/$pubkey",
            "https://mint.example:123456/p/$pubkey",
            "https://mint.example:99999/p/$pubkey",
        )
        for (text in notPages) assertNull(text, Collection.parse(text))
    }
}
