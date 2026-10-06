package cash.nonfungible.app.entry

import android.util.Base64
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.upokecenter.cbor.CBORException
import com.upokecenter.cbor.CBORObject
import com.upokecenter.cbor.CBORType
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.bouncycastle.util.encoders.DecoderException
import org.bouncycastle.util.encoders.Hex
import java.security.MessageDigest
import java.security.SecureRandom

/** A collection is the address of its page on the site that issued its tickets. */
data class Collection(val url: String, val site: String, val pubkey: String) {
    /** A name for what the phone keeps about this collection, which no other collection has. */
    val key: String
        get() = Hex.toHexString(MessageDigest.getInstance("SHA-256").digest(url.toByteArray()), 0, 16)

    companion object {
        // Plain http only reaches a site on this machine, for trying things out.
        private val PAGE = Regex(
            "^((?:https://[a-z0-9.-]+|http://(?:localhost|127\\.0\\.0\\.1))(?::[0-9]{1,5})?)" +
                "/p/([0-9a-f]{64})$"
        )

        /** Whether a text is written as a collection's address is: what a ticket's metadata must hold. */
        fun isAddress(text: String): Boolean = PAGE.matches(text)

        /**
         * The collection at this address, or null if it is not a page's address. A collection
         * has one address: a port that goes without saying is not part of it, so the same
         * page written two ways is one collection with one record of who came in.
         */
        fun parse(url: String): Collection? {
            val (written, pubkey) = PAGE.matchEntire(url)?.destructured ?: return null
            val address = written.toHttpUrlOrNull() ?: return null
            val usual = address.port == HttpUrl.defaultPort(address.scheme)
            val site = "${address.scheme}://${address.host}" + if (usual) "" else ":${address.port}"
            return Collection("$site/p/$pubkey", site, pubkey)
        }
    }
}

/** What a door shows: a request for NFTs of a collection, answered over nostr. */
data class Asked(
    val encoded: String,
    val id: String,
    val collection: Collection,
    val door: String,
) {
    /** Whether an answer is to this request: its id, and every proof one of exactly its text. */
    fun isAnsweredBy(answer: Answer): Boolean = answer.id == id &&
        answer.showings.all { it.context.contentEquals(Entry.context(encoded)) }
}

/** A holder's proof: a showing of one NFT, bound to a context. */
class Showing(val context: ByteArray, val presentation: ByteArray) {
    /** The asset hash the presentation shows. */
    val assetHash: String get() = Hex.toHexString(presentation, 33, 32)

    /** The keyset of the mint the credential is from, as the presentation names it. */
    val keyset: String get() = Hex.toHexString(presentation, 0, 33)

    /** What every showing of one credential has in common, and no other credential's has. */
    val nullifier: String get() = Hex.toHexString(presentation, 209, 48)
}

/**
 * What a holder sent: the NFTs they show, each one once, and the collection they say they are,
 * for a holder who wants to be greeted. A holder who would rather not say sends from a key made
 * for the occasion and names nobody.
 */
class Answer(val id: String, val showings: List<Showing>, val sender: String? = null)

/**
 * An answer handed straight to the door, by a tap or as a code the door scans: which request it
 * answers, a presentation of each NFT, and the collection its holder says they are. The door
 * knows what it asked, so the request does not come back with it.
 */
class Direct(val id: String, val presentations: List<ByteArray>, val sender: String?) {
    /** This as an answer to a request: every presentation must then be a showing of that request. */
    fun to(asked: Asked): Answer =
        Answer(id, presentations.map { Showing(Entry.context(asked.encoded), it) }, sender)
}

/** The request and answer of NUT-XX "Entry". */
object Entry {
    private const val UNIT = "ticket"
    private const val DOMAIN = "Cashu_NFT_Ticket_Entry_v1"
    private const val SHOWING_DOMAIN = "Cashu_PS_Showing_v1"
    private const val PROOF_PREFIX = "pshow1"
    private const val PRESENTATION_SIZE = 321
    private val DIRECT = Regex("^ticket:([0-9a-f]{32})((?::[0-9a-f]{${2 * PRESENTATION_SIZE}}){1,10})(?::([0-9a-f]{64}))?$")

    /** The most NFTs one answer may show: a group that arrives together. */
    const val MOST = 10

    /** A fresh NUT-18 request for a collection's NFTs, answered to `nprofile`. */
    fun request(
        collection: Collection,
        door: String,
        nprofile: String,
        id: String = Hex.toHexString(ByteArray(16).also(SecureRandom()::nextBytes)),
    ): Asked {
        val transport = CBORObject.NewOrderedMap()
            .Add("t", "nostr")
            .Add("a", nprofile)
            .Add("g", CBORObject.NewArray().Add(CBORObject.NewArray().Add("n").Add("17")))
        val body = CBORObject.NewOrderedMap()
            .Add("i", id)
            .Add("u", UNIT)
            .Add("s", true)
            .Add("m", CBORObject.NewArray().Add(collection.url))
            .Add("d", door)
            .Add("t", CBORObject.NewArray().Add(transport))
        val flags = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        val encoded = "creqA" + Base64.encodeToString(body.EncodeToBytes(), flags)
        return Asked(encoded, id, collection, door)
    }

    /** What the holder signs: the whole request, as shown. */
    fun context(request: String): ByteArray = "$DOMAIN\n$request".toByteArray()

    /** What the mint verifies a showing of `context` against. */
    fun binding(context: ByteArray): ByteArray =
        SHOWING_DOMAIN.toByteArray() + uint16(context.size) + context

    /**
     * The answer a rumor carries: an id and up to [MOST] proofs, no two of one asset. Null for
     * anything else. `signer` is the key that signed the rumor's seal: the answer is from the
     * collection it names only if that collection's key is the one that signed.
     */
    fun answer(content: String, signer: String? = null): Answer? {
        val body = try {
            JsonParser.parseString(content) as? JsonObject
        } catch (e: JsonParseException) {
            null
        } ?: return null
        val id = body.get("id").text() ?: return null
        val proofs = (body.get("proofs") as? JsonArray)?.takeIf { it.size() in 1..MOST } ?: return null
        val showings = proofs.map { showing(it.text() ?: return null) ?: return null }
        if (showings.distinctBy(Showing::assetHash).size != showings.size) return null
        return Answer(id, showings, body.get("sender").text()?.takeIf { it == signer })
    }

    /**
     * The request a code or a tap carries, or null if it is not a request for NFTs: what a
     * holder's phone reads, with nothing asked of anybody.
     */
    fun asked(request: String): Asked? {
        if (!request.startsWith("creqA")) return null
        val body = try {
            CBORObject.DecodeFromBytes(Base64.decode(request.substring(5), Base64.URL_SAFE))
        } catch (e: CBORException) {
            return null
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (body.type != CBORType.Map || body["u"].string() != UNIT) return null
        val id = body["i"].string() ?: return null
        val mints = body["m"]?.takeIf { it.type == CBORType.Array && it.size() == 1 } ?: return null
        val collection = mints[0].string()?.let(Collection::parse) ?: return null
        return Asked(request, id, collection, body["d"].string().orEmpty().take(60))
    }

    private fun CBORObject?.string(): String? = this?.takeIf { it.type == CBORType.TextString }?.AsString()

    /**
     * The answer a holder handed over directly: `TICKET`, the request's id, one to [MOST]
     * presentations with no two of one asset, and perhaps the holder's collection. Null for
     * anything else.
     */
    fun direct(text: String): Direct? {
        val (id, shown, sender) = DIRECT.matchEntire(text.trim().lowercase())?.destructured ?: return null
        val presentations = shown.split(':').filter { it.isNotEmpty() }.map(Hex::decodeStrict)
        val assets = presentations.map { Hex.toHexString(it, 33, 32) }
        if (assets.distinct().size != assets.size) return null
        return Direct(id, presentations, sender.ifEmpty { null })
    }

    /** The showing a `pshow1` proof carries, or null if it is not one. */
    fun showing(proof: String): Showing? {
        if (!proof.startsWith(PROOF_PREFIX)) return null
        val raw = try {
            Hex.decodeStrict(proof.substring(PROOF_PREFIX.length))
        } catch (e: DecoderException) {
            return null
        }
        if (raw.size < 2) return null
        val length = (raw[0].toInt() and 0xff shl 8) or (raw[1].toInt() and 0xff)
        if (raw.size != 2 + length + PRESENTATION_SIZE) return null
        return Showing(raw.copyOfRange(2, 2 + length), raw.copyOfRange(2 + length, raw.size))
    }

    private fun uint16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())
}

/** The text of a JSON string, or null for anything else. */
fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.asString

/** A JSON number that is a whole number a phone can count to, or null for anything else. */
fun JsonElement?.whole(): Long? =
    (this as? JsonPrimitive)?.takeIf { it.isNumber }?.asString?.toLongOrNull()
