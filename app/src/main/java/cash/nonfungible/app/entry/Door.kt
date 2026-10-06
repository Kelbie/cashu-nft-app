package cash.nonfungible.app.entry

import android.content.Context
import android.os.Parcelable
import android.util.Log
import cash.nonfungible.app.nostr.NostrEvent
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.parcelize.Parcelize
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.bouncycastle.util.encoders.DecoderException
import org.bouncycastle.util.encoders.Hex
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

enum class Refusal { NOT_VALID, NOT_OWNER, NOT_ISSUED, NOT_CHECKED }

/** What the door decided about one NFT. */
sealed interface Verdict : Parcelable {
    @Parcelize
    data object Admitted : Verdict

    /** The holder owns an NFT of the collection, and it was let in before. */
    @Parcelize
    data class AlreadyIn(val earlier: Admission) : Verdict

    @Parcelize
    data class Refused(val reason: Refusal) : Verdict
}

@Parcelize
data class Decision(val h: String, val verdict: Verdict) : Parcelable

/**
 * Who showed the NFTs: the collection the answer named, whose page claims a credential the
 * answer showed.
 */
@Parcelize
data class Holder(val pubkey: String, val name: String) : Parcelable

/** One answer at the door: what was decided about each NFT it showed, in the order shown. */
@Parcelize
data class Arrival(
    val collection: String,
    val decisions: List<Decision>,
    val holder: Holder? = null,
) : Parcelable {
    /** No proof in the answer was a real one. No wallet sends such an answer by mistake. */
    val fake: Boolean
        get() = decisions.all { (it.verdict as? Verdict.Refused)?.reason == Refusal.NOT_VALID }
}

/**
 * Decides whether an answer lets its holder in: NUT-XX "What the door checks". The mint that
 * issued the collection verifies each proof, so the phone carries no pairing code. Anything the
 * door cannot check refuses, and what costs nothing is checked before the mint is asked.
 */
class Door(
    private val context: Context,
    private val patienceMs: Long = 20_000,
    private val retryMs: Long = 800,
) {
    /**
     * Blocks on the network, for no longer than a holder should wait. Anybody who can see the
     * door's code can send it something shaped like an answer, so nothing is spent or recorded
     * for one the mint disowns: `go` is asked only once the mint has borne a proof out, and
     * says whether this answer is still the one to let in. Null when it says no.
     */
    fun decide(answer: Answer, asked: Asked, go: () -> Boolean = { true }): Arrival? {
        val until = now() + patienceMs
        val collection = asked.collection
        val none = Verdict.Refused(Refusal.NOT_VALID)
        if (!asked.isAnsweredBy(answer)) {
            return Arrival(collection.url, answer.showings.map { Decision(it.assetHash, none) })
        }
        // The mint is asked about every NFT at once; the record is then kept one at a time.
        val owned = answer.showings.map { POOL.submit(Callable { owned(it, collection.site, until) }) }
        val refusals = owned.map { checked(it) ?: Checked(Refusal.NOT_CHECKED) }
        if (refusals.all { it.refusal == Refusal.NOT_VALID }) {
            return Arrival(collection.url, answer.showings.map { Decision(it.assetHash, none) })
        }
        if (!go()) return null
        // A holder who asked to be named is looked up while the record is kept.
        val page = answer.sender?.let { named ->
            POOL.submit(Callable { claims(collection.site, named, answer.showings, minOf(until, now() + GREETING_MS)) })
        }
        val decisions = answer.showings.zip(refusals) { showing, checked ->
            val h = showing.assetHash
            Decision(h, checked.refusal?.let(Verdict::Refused) ?: admit(h, asked, until))
        }
        // A name is only for somebody whose page claims a credential of an NFT that was let in
        // on this answer, or had been before.
        val welcome = answer.showings.zip(decisions).filter { it.second.verdict !is Verdict.Refused }
            .map { it.first.nullifier }
        val holder = page?.let(::checked)?.takeIf { claims -> welcome.any { it in claims.nullifiers } }?.holder
        return Arrival(collection.url, decisions, holder)
    }

    /** Time that only goes forward: a phone's clock is set while it is in use. */
    private fun now(): Long = System.nanoTime() / 1_000_000

    /** Why a showing was refused: nothing, when the mint bears it out. */
    private class Checked(val refusal: Refusal?)

    /** A collection's page: what it calls itself, and the credentials it claims as its own. */
    private class Claims(val holder: Holder, val nullifiers: Set<String>)

    /** What a check found, or null if it failed in a way nobody foresaw. Nothing is let in for that. */
    private fun <T> checked(check: Future<T>): T? = try {
        check.get()
    } catch (e: ExecutionException) {
        Log.e(TAG, "A check failed", e.cause)
        null
    }

    /**
     * Reads the collection's page and keeps the NFTs it lists. Returns the collection's name,
     * or null if the site does not answer with one. Blocks.
     */
    fun sync(collection: Collection): String? = try {
        read(collection, now() + patienceMs)
    } catch (e: IOException) {
        Log.e(TAG, "Could not read the collection: ${e.message}")
        null
    }

    /** Whether the mint bears the showing out as its holder's, and why not when it does not. */
    private fun owned(showing: Showing, site: String, until: Long): Checked = Checked(refusal(showing, site, until))

    private fun refusal(showing: Showing, site: String, until: Long): Refusal? = try {
        val proof = JsonObject().apply {
            addProperty("presentation", Hex.toHexString(showing.presentation))
            addProperty("binding", Hex.toHexString(Entry.binding(showing.context)))
        }
        val verify = Request.Builder().url("$site/v1/nft/verify")
            .post(proof.toString().toRequestBody(JSON))
        val verified = try {
            json(verify, until)
        } catch (e: Rejected) {
            // 400 is the mint saying it could not even read the proof.
            if (e.code == 400) null else throw e
        }
        when {
            verified == null || !verified.flag("valid") -> Refusal.NOT_VALID
            verified.flag("spent") -> Refusal.NOT_OWNER
            else -> {
                val asset = Request.Builder().url("$site/v1/nft/asset/${showing.assetHash}")
                val status = json(asset, until).get("status").text()
                    ?: throw IOException("The mint did not say whether the asset is active")
                if (status == "active") null else Refusal.NOT_OWNER
            }
        }
    } catch (e: IOException) {
        Log.e(TAG, "Could not check a proof: ${e.message}")
        Refusal.NOT_CHECKED
    }

    /** Lets in an NFT its holder owns, if it is the collection's and has not come in before. */
    private fun admit(h: String, asked: Asked, until: Long): Verdict = try {
        if (!lists(asked.collection, h, until)) {
            Verdict.Refused(Refusal.NOT_ISSUED)
        } else {
            Admitted(context, asked.collection).record(h, asked.door)?.let(Verdict::AlreadyIn)
                ?: Verdict.Admitted
        }
    } catch (e: IOException) {
        Log.e(TAG, "Could not admit an NFT: ${e.message}")
        Verdict.Refused(Refusal.NOT_CHECKED)
    } catch (e: RuntimeException) {
        // Whatever nobody foresaw about one NFT refuses that one, and says nothing about the others.
        Log.e(TAG, "Could not admit an NFT", e)
        Verdict.Refused(Refusal.NOT_CHECKED)
    }

    /** Whether the collection lists the asset. Its page is read again only for one not saved. */
    private fun lists(collection: Collection, h: String, until: Long): Boolean {
        val tickets = Tickets(context, collection)
        if (h in tickets) return true
        read(collection, until)
        return h in tickets
    }

    /** A collection's page lists every NFT it holds or has passed on. */
    private fun read(collection: Collection, until: Long): String? {
        val page = json(
            Request.Builder().url("${collection.site}/api/profiles/${collection.pubkey}"), until
        )
        val cards = page.get("cards") as? JsonArray
            ?: throw IOException("The collection did not list its NFTs")
        Tickets(context, collection).save(cards)
        return page.get("name").text()
    }

    /**
     * The page of the collection that signed the answer, if it has one. A page's claim is a
     * showing of a credential that names the page's key, signed with that key; every showing
     * of one credential carries the same nullifier.
     */
    private fun claims(site: String, pubkey: String, shown: List<Showing>, until: Long): Claims? = try {
        val page = json(Request.Builder().url("$site/api/profiles/$pubkey"), until)
        val cards = (page.get("cards") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
        val holder = Holder(pubkey, page.get("name").text().orEmpty())
        // Only the claims of what was shown are worth a signature check: a page may list thousands.
        val wanted = shown.map { it.nullifier }.toSet()
        Claims(holder, cards.mapNotNull { claimed(it, pubkey, wanted) }.toSet())
    } catch (e: IOException) {
        Log.d(TAG, "No page for the answer's sender: ${e.message}")
        null
    }

    /**
     * The nullifier of the credential a card claims for `pubkey`, if it is one of `wanted`, or
     * null. The claim's context is exactly the one a collection writes: its key, the asset and
     * the keyset the credential is from.
     */
    private fun claimed(card: JsonObject, pubkey: String, wanted: Set<String>): String? {
        if (card.get("status").text() == "sent") return null
        val text = card.get("showing").text() ?: return null
        val showing = Entry.showing(text)?.takeIf { it.nullifier in wanted } ?: return null
        val claim = "$CLAIM_CONTEXT\n$pubkey\n${showing.assetHash}\n${showing.keyset}"
        if (String(showing.context) != claim) return null
        val signed = MessageDigest.getInstance("SHA-256").digest("$CLAIM\n$text".toByteArray())
        val signature = try {
            Hex.decodeStrict(card.get("signature").text().orEmpty())
        } catch (e: DecoderException) {
            return null
        }
        if (!NostrEvent.verifySchnorr(Hex.decodeStrict(pubkey), signed, signature)) return null
        return showing.nullifier
    }

    /**
     * Asks the site, and asks again when it is busy or the network drops: a holder should not
     * have to scan twice for that. What the site refuses outright is not asked again.
     */
    private fun json(request: Request.Builder, until: Long): JsonObject {
        var failure = IOException("The site took too long")
        for (attempt in 0 until ATTEMPTS) {
            val left = until - now() - attempt * retryMs
            if (left <= 0) break
            Thread.sleep(attempt * retryMs)
            try {
                val call = HTTP.newCall(request.build())
                call.timeout().timeout(left, TimeUnit.MILLISECONDS)
                return call.execute().use(::read)
            } catch (e: Rejected) {
                throw e
            } catch (e: IOException) {
                failure = e
            }
        }
        throw failure
    }

    private fun read(response: Response): JsonObject {
        if (response.code in 400..499 && response.code != 429) throw Rejected(response.code)
        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
        return try {
            JsonParser.parseString(response.body?.string().orEmpty()) as? JsonObject
        } catch (e: JsonParseException) {
            null
        } ?: throw IOException("The answer was not a JSON object")
    }

    /** The site understood the request and said no. */
    private class Rejected(val code: Int) : IOException("HTTP $code")

    private fun JsonObject.flag(name: String): Boolean =
        (get(name) as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean
            ?: throw IOException("The mint did not say whether the proof is $name")

    companion object {
        private const val TAG = "Door"
        private const val CLAIM_CONTEXT = "Cashu_NFT_Portfolio_Show_v1"
        private const val CLAIM = "Cashu_NFT_Portfolio_Claim_v1"
        private val JSON = "application/json".toMediaType()
        private const val ATTEMPTS = 3

        // A greeting is worth a moment and no more: nobody waits at a door for their own name.
        private const val GREETING_MS = 2500L
        private val HTTP = OkHttpClient()

        // An answer shows at most ten NFTs; a few at a time keeps a group quick and the site calm.
        private val POOL = Executors.newFixedThreadPool(4)
    }
}
