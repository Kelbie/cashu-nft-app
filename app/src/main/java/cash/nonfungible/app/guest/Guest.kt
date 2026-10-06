package cash.nonfungible.app.guest

import android.content.Context
import android.util.Log
import android.view.ViewGroup
import cash.nonfungible.app.Account
import cash.nonfungible.app.Config
import cash.nonfungible.app.R
import cash.nonfungible.app.App
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Entry
import cash.nonfungible.app.entry.Ticket
import cash.nonfungible.app.entry.Tickets
import cash.nonfungible.app.entry.text
import cash.nonfungible.app.entry.whole
import cash.nonfungible.app.studio.Minter
import cash.nonfungible.app.studio.Prices
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What a guest scanned or opened: one of the four things a code can be for them. */
sealed interface Scanned {
    /** A door asking to see a ticket. */
    data class Door(val request: String) : Scanned

    /** A ticket given by a link: whoever opens it first has it. */
    data class Gift(val link: String) : Scanned

    /** A ticket for sale, and where its seller wants to be paid, if the code said. */
    data class Sale(val listing: String, val mint: String?) : Scanned

    /** An event's own page: what it has for sale. */
    data class Event(val collection: Collection) : Scanned

    companion object {
        private val GIFT = Regex("^/claim/[0-9a-f]{32}$")
        private val SALE = Regex("^/market/([0-9a-f]{32})$")
        private val FRAGMENT = Regex("^[A-Za-z0-9_-]{43}$")

        /** What a code or a link means to a guest of `config`'s site, or null if nothing. */
        fun read(text: String, config: Config): Scanned? {
            val given = text.trim()
            if (given.startsWith("creqA")) return Door(given)
            val address = given.toHttpUrlOrNull()?.takeIf { config.onSite(given) } ?: return null
            val path = address.encodedPath
            return when {
                // The link as the site writes it: whatever else was added to it on the way is left behind.
                GIFT.matches(path) && FRAGMENT.matches(address.fragment.orEmpty()) -> Gift("${config.site}$path#${address.fragment}")
                SALE.matches(path) -> Sale(SALE.find(path)?.groupValues?.get(1).orEmpty(), address.queryParameter("mint"))
                else -> Collection.parse(given.substringBefore('#').substringBefore('?'))?.let(::Event)
            }
        }
    }
}

/** One of the guest's NFTs that a door would let in. */
data class Mine(val id: String, val h: String, val title: String)

/**
 * What a door asks: its name, the collection it is the door of, and the guest's NFTs for it.
 * `sure` when the phone knows those are the ones the door lets in, and is not just offering
 * everything it holds for want of anybody to ask.
 */
data class AtDoor(val door: String, val event: String, val mine: List<Mine>, val sure: Boolean = true)

/** A collection, looked through: what it is called, every NFT it lists, and those for sale by their asset. */
class Browsed(val name: String, val listed: List<Ticket>, val selling: Map<String, ForSale>)

/** An NFT on sale on the site's market. */
data class ForSale(
    val listing: String,
    val h: String,
    val title: String,
    val price: Long,
    val seller: String,
    val sellerName: String,
    val mint: String? = null,
)

/** A mint the wallet can keep money at, and what is there: to spend, tied up in a sale, and on its way. */
data class At(val name: String, val url: String, val test: Boolean, val available: Long, val locked: Long, val pending: Long)

/** Something that happened to the wallet's money: how it came or went, how much, where and when. */
data class Moved(val type: String, val amount: Long, val mint: String, val at: Long, val state: String)

/** The wallet at a glance: each of its mints, and what has lately come and gone. */
class Purse(val mints: List<At>, val history: List<Moved>, val settling: Int = 0) {
    fun at(url: String): At? = mints.firstOrNull { it.url.trimEnd('/') == url.trimEnd('/') }
}

/** What paying an invoice would cost: the name to pay it by, the amount, and the most the fee can be. */
data class Quote(val id: String, val amount: Long, val fee: Long)

/**
 * An account's side of things, without the website: their own collection, and what they do with
 * it. They take a ticket they are given, buy one, show one at a door, give one away and sell
 * one on. Every one of those is the site's own protocol, run by the site's own wallet code in
 * a page nobody sees; this class is what the screens ask.
 */
class Guest(private val app: App, private val account: Account) {
    private val prefs = app.getSharedPreferences(account.store("guest"), Context.MODE_PRIVATE)
    private val oneAtATime = Mutex()
    private var page: Minter? = null

    /** The guest's own collection, once they have been given or have bought something. */
    val me: Collection? get() = prefs.getString(ME, null)?.let(Collection::parse)

    /** What the account is called, which is what a door greets its holder by. The site is told when it is next asked anything. */
    val name: String get() = app.accounts.all.firstOrNull { it.id == account.id }?.name ?: account.name

    /**
     * The mint the account is paid at for what it sells, and pays from when a seller does not
     * say where. It does not change while something is for sale: an offer paid at the old one
     * would wait for nobody.
     */
    val mint: String get() = prefs.getString(MINT, null) ?: Prices.TEST_MINT

    /** The mint the wallet is showing, which is where money is added and taken out. */
    val viewing: String get() = prefs.getString(VIEWING, null) ?: mint

    /** Shows another mint in the wallet, and is paid there from now on unless something is for sale. */
    fun view(url: String) {
        val chosen = prefs.edit().putString(VIEWING, url)
        if (asks.isEmpty()) chosen.putString(MINT, url)
        chosen.apply()
    }

    /** The wallet as the site last told it, or null before it has. Nobody is asked. */
    val purse: Purse?
        get() = try {
            (JsonParser.parseString(prefs.getString(PURSE, null) ?: "") as? JsonObject)?.let(::purse)
        } catch (e: JsonParseException) {
            null
        }

    private fun purse(said: JsonObject) = Purse(
        (said.get("mints") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty().mapNotNull { at ->
            At(
                at.get("name").text().orEmpty(), at.get("url").text() ?: return@mapNotNull null,
                at.get("test")?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
                at.get("available").whole() ?: 0, at.get("locked").whole() ?: 0, at.get("pending").whole() ?: 0,
            )
        },
        (said.get("history") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty().map { moved ->
            Moved(
                moved.get("type").text().orEmpty(), moved.get("amount").whole() ?: 0, moved.get("mint").text().orEmpty(),
                moved.get("at").whole() ?: 0, moved.get("state").text().orEmpty(),
            )
        },
        said.get("settling").whole()?.toInt() ?: 0,
    )

    /**
     * The wallet as it stands: what is at each mint, and what has lately come and gone. An
     * account the site has not heard of holds nothing: its mints are listed, empty, and no
     * collection is made on the site just for having looked.
     */
    suspend fun wallet(): Purse = oneAtATime.withLock {
        val mine = me
        val said = if (mine != null) minter().call("money", mine.pubkey).asJsonObject else JsonObject().apply {
            add("mints", JsonArray().apply {
                for (named in minter().call("mints").asJsonArray.filterIsInstance<JsonObject>()) {
                    add(named.deepCopy().apply {
                        for (sum in listOf("available", "locked", "pending")) addProperty(sum, 0)
                    })
                }
            })
        }
        prefs.edit().putString(PURSE, said.toString()).apply()
        purse(said).also { now -> now.at(mint)?.let { prefs.edit().putLong(HAD, it.available).apply() } }
    }

    /** Takes in an ecash token: where it was, and how many sats. */
    suspend fun receive(token: String): Pair<String, Long> = oneAtATime.withLock {
        val taken = minter().call("receive", ensureLocked().pubkey, token).asJsonObject
        taken.get("mint").text().orEmpty() to (taken.get("amount").whole() ?: 0)
    }

    /** What paying a Lightning invoice out of the wallet at a mint would cost. Nothing is paid yet. */
    suspend fun quote(invoice: String, at: String = viewing): Quote = oneAtATime.withLock {
        val asked = minter().call("quote", ensureLocked().pubkey, at, invoice).asJsonObject
        Quote(asked.get("id").text().orEmpty(), asked.get("amount").whole() ?: 0, asked.get("fee").whole() ?: 0)
    }

    /**
     * Pays the invoice a quote was for: whether the mint has paid it. Once begun it is seen
     * through. A payment the mint is still making when this stops waiting is not paid yet, and
     * is not said to be: it shows in the wallet when it is, or its sats come back.
     */
    suspend fun pay(quote: Quote): Boolean = oneAtATime.withLock {
        val mine = ensureLocked()
        withContext(NonCancellable) { minter().call("pay", mine.pubkey, quote.id) }.text() == "finalized"
    }

    /**
     * Lets go of a quote that will not be paid. Asking what an invoice costs sets the sats to
     * pay it aside, and they are the wallet's to spend again only once this is said.
     */
    suspend fun unquote(quote: Quote) {
        val mine = me ?: return
        try {
            oneAtATime.withLock { withContext(NonCancellable) { minter().call("unquote", mine.pubkey, quote.id) } }
        } catch (e: IOException) {
            Log.e(TAG, "Could not let go of a quote: ${e.message}")
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Letting go of a quote took too long")
        }
    }

    /** What the guest has put up for sale: an NFT's card and its price in sats. */
    val asks: Map<String, Long>
        get() = try {
            (JsonParser.parseString(prefs.getString(ASKS, null) ?: "{}") as? JsonObject)?.entrySet().orEmpty()
                .mapNotNull { (card, price) -> price.whole()?.let { card to it } }.toMap()
        } catch (e: JsonParseException) {
            emptyMap()
        }

    /** What the guest could spend when the site was last asked. */
    val had: Long get() = prefs.getLong(HAD, 0)

    /**
     * Whether the site may know something this phone does not: the guest has something on sale,
     * has paid for something that had not arrived, or has a link out that gives something away.
     * Otherwise what a guest holds changes only by what they do here, and a phone at a door has
     * no need to ask.
     */
    val expecting: Boolean get() = asks.isNotEmpty() || prefs.getLong(AWAITING, 0) > System.currentTimeMillis()

    /** From now and for a day and a half, somebody else may change what the guest holds. */
    private fun expect() = prefs.edit().putLong(AWAITING, System.currentTimeMillis() + AWAIT_MS).apply()

    /** Money taken out as an ecash token, kept until the guest says they have it. */
    var takenOut: String?
        get() = prefs.getString(TAKEN_OUT, null)
        set(token) {
            // Written before anything else can happen: for a while this is the only copy of the money.
            prefs.edit().putString(TAKEN_OUT, token).commit()
        }

    /** Puts the page that does the work in a window: a page in none may never be let through by the site. */
    fun attach(into: ViewGroup) {
        val view = minter().window
        (view.parent as? ViewGroup)?.removeView(view)
        into.addView(view)
    }

    private fun minter(): Minter = page ?: Minter(app, Config(app).site).also { page = it }

    /** Takes the page out of a screen that is going away, so that the screen can go. */
    fun detach(from: ViewGroup) {
        page?.window?.let { if (it.parent === from) from.removeView(it) }
    }

    /** The page is put away: the account is gone from this phone. */
    fun close() {
        page?.close()
        page = null
    }

    /** The guest's collection, made the first time it is needed. Nobody is asked anything for it. */
    suspend fun ensure(): Collection = me ?: oneAtATime.withLock { ensureLocked() }

    /**
     * Has the site call the guest's collection what the guest last said, if it does not yet.
     * For a caller that holds the lock. A name that could not be told is told the next time.
     */
    private suspend fun tellName(mine: Collection) {
        val called = name.ifEmpty { UNNAMED }
        if (prefs.getString(TOLD, UNNAMED) == called) return
        try {
            minter().call("rename", mine.pubkey, called)
            prefs.edit().putString(TOLD, called).apply()
        } catch (e: IOException) {
            Log.e(TAG, "Could not tell the site the guest's name: ${e.message}")
        }
    }

    /** The guest's NFTs as the phone last knew them. Nobody is asked. */
    val held: List<Ticket> get() = me?.let { mine -> Tickets(app, mine).all.filter { !it.sent } }.orEmpty()

    /** The guest's NFTs as the site lists them now, with whatever the market owed them brought in. */
    suspend fun mine(): List<Ticket> {
        val mine = me ?: return emptyList()
        try {
            oneAtATime.withLock {
                tellName(mine)
                collect(mine)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not collect: ${e.message}")
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Collecting took too long")
        }
        return held
    }

    /** Asks the site what the guest holds and can spend, and keeps both. For a caller that holds the lock. */
    private suspend fun collect(mine: Collection, at: String = mint): List<Ticket> {
        val found = minter().call("collect", mine.pubkey, at).asJsonObject
        (found.get("cards") as? JsonArray)?.let { Tickets(app, mine).save(it) }
        if (at.trimEnd('/') == mint.trimEnd('/')) found.get("balance").whole()?.let { prefs.edit().putLong(HAD, it).apply() }
        // What is no longer the guest's is no longer for sale: it sold, or was sent, somewhere else.
        val still = held.map(Ticket::id).toSet()
        if (asks.keys.any { it !in still }) save(asks.filterKeys { it in still })
        return held
    }

    /** Takes a ticket given by a link. Returns its asset hash and what it is listed as. */
    suspend fun claim(link: String): Mine = oneAtATime.withLock {
        val mine = ensureLocked()
        val card = minter().call("claim", mine.pubkey, link).asJsonObject
        val taken = Mine(card.get("id").text().orEmpty(), card.get("h").text().orEmpty(), card.get("title").text().orEmpty())
        // The phone knows it has it without asking again.
        Tickets(app, mine).add(Ticket(taken.h, taken.title, System.currentTimeMillis() / 1000, id = taken.id))
        taken
    }

    /**
     * What a door asks and which of the guest's NFTs it would let in, from what the phone holds:
     * nobody is asked, so it is known at once and with no network. A ticket says in its own
     * picture whose door lets it in, and the phone remembers what a door has let it show before.
     * Null when the request is not one, or is another site's.
     */
    fun atDoor(request: String, prefer: String? = null): AtDoor? {
        val asked = Entry.asked(request)?.takeIf { it.collection.site == Config(app).site } ?: return null
        val door = asked.collection.url
        val held = held.filter { it.id.isNotEmpty() }
        val theirs = held.filter { app.pictures.about(it.h)?.metadata?.opens(asked.collection.pubkey) == true || admittedBy(it.h) == door }
        // The one the guest came from goes first: it is the one shown when only one is.
        val known = theirs.sortedByDescending { it.h == prefer }.take(Entry.MOST).map(::mine)
        if (known.isNotEmpty()) return AtDoor(asked.door, events[door].orEmpty(), known)
        // A guest who opened an NFT and went to a door with it means that one. The door decides.
        val meant = held.firstOrNull { it.h == prefer }?.let { listOf(mine(it)) }.orEmpty()
        return AtDoor(asked.door, events[door].orEmpty(), meant, sure = false)
    }

    private fun mine(ticket: Ticket) = Mine(ticket.id, ticket.h, ticket.title)

    /**
     * As [atDoor], but asks the site when the phone does not know: an NFT that does not say
     * whose door lets it in is looked for on that door's collection. What is learned is kept.
     */
    suspend fun asked(request: String, prefer: String? = null): AtDoor {
        val known = atDoor(request, prefer)
        if (known?.sure == true) return known
        // Somebody who holds nothing is not given a collection for having read a door's code.
        if (me == null) return known ?: throw IOException(app.getString(R.string.guest_not_ours))
        // With no network the guest is not kept waiting: the NFT they came from is what they mean.
        fun unsure(why: String?): AtDoor = known?.takeIf { it.mine.isNotEmpty() } ?: throw IOException(why)
        return try {
            withTimeout(ASK_MS) {
                oneAtATime.withLock {
                    val mine = ensureLocked()
                    val found = minter().call("asked", mine.pubkey, request).asJsonObject
                    val theirs = (found.get("mine") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty().take(Entry.MOST)
                        .map { Mine(it.get("id").text().orEmpty(), it.get("h").text().orEmpty(), it.get("title").text().orEmpty()) }
                        .sortedByDescending { it.h == prefer }
                    val event = found.get("event").text().orEmpty()
                    Entry.asked(request)?.collection?.url?.let { door -> learn(door, event, theirs.map(Mine::h)) }
                    AtDoor(found.get("door").text().orEmpty(), event, theirs)
                }
            }
        } catch (e: IOException) {
            unsure(e.message)
        } catch (e: TimeoutCancellationException) {
            unsure(app.getString(R.string.guest_door_no_network))
        }
    }

    /** Which collection's door each NFT is known to get through, and what each collection is called. */
    private val doors: Map<String, String> get() = texts(DOORS)
    private val events: Map<String, String> get() = texts(EVENTS)

    private fun admittedBy(h: String): String? = doors[h]

    private fun texts(kept: String): Map<String, String> = try {
        (JsonParser.parseString(prefs.getString(kept, null) ?: "{}") as? JsonObject)?.entrySet().orEmpty()
            .mapNotNull { (key, value) -> value.text()?.let { key to it } }.toMap()
    } catch (e: JsonParseException) {
        emptyMap()
    }

    private fun learn(door: String, event: String, hashes: List<String>) {
        fun kept(all: Map<String, String>) = JsonObject().apply { for ((key, value) in all) addProperty(key, value) }.toString()
        prefs.edit()
            .putString(DOORS, kept(doors + hashes.associateWith { door }))
            .putString(EVENTS, kept(if (event.isEmpty()) events else events + (door to event)))
            .apply()
    }

    /**
     * Shows NFTs to the door that asked, over the relays its request names. A guest who has
     * given a name is greeted by it. This needs a network; [prove] does not.
     */
    suspend fun show(request: String, shown: List<Mine>) = oneAtATime.withLock {
        val mine = ensureLocked()
        tellName(mine)
        val ids = JsonArray().apply { shown.forEach { add(it.id) } }.toString()
        minter().call("answer", mine.pubkey, request, ids, if (name.isNotEmpty()) "yes" else "")
        shownAt(shown)
    }

    /**
     * The answer to a door, to hand to it directly: by holding the phone to it, or as a code it
     * scans. It is made from what the phone holds and nothing else, so a guest with no network
     * gets in as long as the door has one. Other work is not waited for: this only reads.
     */
    suspend fun prove(request: String, shown: List<Mine>): String {
        val mine = me ?: throw IOException("This phone holds no NFTs yet.")
        val cards = JsonArray().apply {
            for (one in shown.take(Entry.MOST)) {
                add(JsonObject().apply {
                    addProperty("id", one.id)
                    addProperty("h", one.h)
                })
            }
        }
        return minter().call("show", mine.pubkey, request, cards.toString(), if (name.isNotEmpty()) "yes" else "")
            .asString.also { shownAt(shown) }
    }

    /** What has been shown at a door is not left for sale: whoever bought it would get one that has been used. */
    private fun shownAt(shown: List<Mine> = emptyList()) {
        val listed = asks.keys.intersect(shown.map(Mine::id).toSet())
        if (listed.isNotEmpty()) save(asks - listed)
    }

    /** A link that gives one of the guest's NFTs to whoever opens it first. */
    suspend fun give(card: String): String = oneAtATime.withLock {
        val mine = ensureLocked()
        // The page takes it off sale before it makes the link. The link is made whatever becomes
        // of the screen that asked for it, and only then is it no longer for sale here.
        withContext(NonCancellable) { minter().link(mine.pubkey, card) }.also {
            save(asks - card)
            expect()
        }
    }

    /** What the guest can spend, in sats. */
    suspend fun balance(): Long = oneAtATime.withLock {
        val mine = me ?: return@withLock 0
        minter().balance(mine.pubkey, mint).also { prefs.edit().putLong(HAD, it).apply() }
    }

    /** A Lightning invoice that puts `sats` into the guest's wallet once paid, and the name to ask about it by. */
    suspend fun invoice(sats: Long, at: String = mint): Pair<String, String> = oneAtATime.withLock {
        val asked = minter().call("invoice", ensureLocked().pubkey, at, sats.toString()).asJsonObject
        asked.get("invoice").text().orEmpty() to asked.get("id").text().orEmpty()
    }

    /** Whether an invoice has been paid and its money is in the wallet. */
    suspend fun paid(id: String): Boolean = oneAtATime.withLock {
        minter().call("paid", ensureLocked().pubkey, id).let { it.isJsonPrimitive && it.asString == "finalized" }
    }

    /**
     * Sats out of the wallet at a mint as an ecash token, kept here until the guest has put it
     * somewhere: so many, or everything there with none said.
     */
    suspend fun takeOut(sats: Long? = null, at: String = viewing): String? = oneAtATime.withLock {
        // One at a time: there is one place to keep a token, and a second would be kept over the first.
        if (takenOut != null) throw IOException(app.getString(R.string.wallet_ecash_first))
        val mine = ensureLocked()
        // Once begun this is seen through: a token nobody was waiting for would be money nobody has.
        withContext(NonCancellable) {
            val all = sats ?: minter().balance(mine.pubkey, at)
            if (all <= 0) null else minter().token(mine.pubkey, at, all).also { takenOut = it }
        }
    }

    /**
     * Makes the account the holder of a key kept from before: what the site lists under that key
     * is the account's, and so is the wallet the key opens. Returns what the site calls it.
     * Only for an account that holds nothing yet, and only a key the site knows.
     */
    suspend fun adopt(key: String): String = oneAtATime.withLock {
        if (me != null) throw IOException("This account already has a key.")
        // The page keeps nothing unless the site knows the key, and refuses one this phone
        // already holds, an account's or a collection's: a restore that fails changes nothing.
        val found = minter().call("adopt", key).asJsonObject
        val mine = found.get("pubkey").text()?.let { Collection.parse("${Config(app).site}/p/$it") }
            ?: throw IOException("The key did not read as one.")
        val called = found.get("name").text().orEmpty()
        prefs.edit().putString(ME, mine.url).putString(TOLD, called).apply()
        called
    }

    /** Whether this phone holds the key to the account's collection, and so its NFTs and its money. */
    suspend fun key(): String? = me?.let { minter().key(it.pubkey) }

    /**
     * Deletes what is the account's on the site, as far as it can be: what it has for sale comes
     * off sale, every NFT it holds is burned, and its name there becomes `gone`, because the
     * site has no way to remove a collection. Then the phone forgets its key. `say` hears how
     * far it has got. Returns how many NFTs it could not burn, and why: with any left, the key
     * is kept, to try again.
     *
     * The key is all there is of the account's money. So before anything is burned the wallet
     * is counted again, and this throws if it holds more than `lose` sats, the sum its owner was
     * shown and agreed to lose, or holds ecash taken out and not yet said to be safe unless they
     * agreed to lose that `tokenToo`. It throws as well if the site cannot be told the new name:
     * the key is kept, and the name is told the next time.
     */
    suspend fun destroy(gone: String, lose: Long, tokenToo: Boolean, say: (burned: Int, of: Int) -> Unit): Pair<Int, String?> = oneAtATime.withLock {
        val mine = me ?: run {
            // The site never heard of it: there is only what the phone kept.
            app.deleteSharedPreferences(account.store("guest"))
            return@withLock 0 to null
        }
        if (takenOut != null && !tokenToo) throw IOException(app.getString(R.string.delete_all_token_first))
        if (minter().wealth(mine.pubkey) > lose) throw IOException(app.getString(R.string.delete_all_more))
        save(emptyMap())
        withContext(NonCancellable) { minter().unlist(mine.pubkey) }
        collect(mine)
        val holding = held.filter { it.id.isNotEmpty() }
        var burned = 0
        var problem: String? = null
        say(0, holding.size)
        for (some in holding.chunked(BURN_AT_ONCE)) {
            val ids = JsonArray().apply { some.forEach { add(it.id) } }.toString()
            val done = withContext(NonCancellable) { minter().patient("burn", mine.pubkey, ids).asJsonObject }
            burned += done.get("burned").whole()?.toInt() ?: 0
            problem = problem ?: (done.get("problems") as? JsonArray)?.firstNotNullOfOrNull { it.text() }
            say(burned, holding.size)
        }
        val left = collect(mine).size
        if (left == 0) {
            // Counted once more: burning takes a while, and something may have been paid meanwhile.
            if (minter().wealth(mine.pubkey) > lose) throw IOException(app.getString(R.string.delete_all_more))
            // Told before the key is forgotten: nobody could tell it afterwards.
            minter().call("rename", mine.pubkey, gone)
            minter().call("forget", mine.pubkey)
            Tickets(app, mine).delete()
            prefs.edit().clear().commit()
            app.deleteSharedPreferences(account.store("guest"))
        }
        left to problem
    }

    /** Every sat the account has anywhere, at any mint: what deleting it would throw away. */
    suspend fun wealth(): Long = oneAtATime.withLock { me?.let { minter().wealth(it.pubkey) } ?: 0 }

    /**
     * Buys an NFT at its price: the wallet is filled if it is short, the price is locked for the
     * seller, and the NFT arrives when the seller hands it over. `say` hears each step: an
     * invoice to pay when money is needed, then that the price is locked. Returns whether the NFT
     * arrived while this waited; one that arrives later is found the next time the guest looks.
     */
    suspend fun buy(sale: ForSale, say: (Step) -> Unit): Boolean {
        val at = (sale.mint ?: mint).trimEnd('/')
        val mine = ensure()
        val pubkey = mine.pubkey
        // A code says where its seller is paid, and anybody can write a code. Money goes only to
        // a mint the guest already uses or the site itself names.
        if (at != mint.trimEnd('/')) {
            val named = oneAtATime.withLock { minter().call("mints").asJsonArray }
                .mapNotNull { (it as? JsonObject)?.get("url").text()?.trimEnd('/') }
            if (at !in named) throw IOException(app.getString(R.string.guest_mint_unknown, at.substringAfter("://")))
        }
        // Nothing is paid for what is no longer for sale.
        if (listing(sale.listing) == null) throw IOException(app.getString(R.string.guest_sale_gone))
        // A guest who has not said where their money is kept keeps it where they first pay: what
        // they sell is then paid for where the collection itself is paid. It is settled before
        // any money moves, so that money is never somewhere the wallet does not look.
        if (!prefs.contains(MINT)) prefs.edit().putString(MINT, at).apply()
        expect()
        val short = oneAtATime.withLock { minter().call("short", pubkey, at, sale.price.toString()).asLong }
        if (short > 0) {
            val (invoice, id) = invoice(short, at)
            say(Step.Pay(invoice, short, at, test = at == Prices.TEST_MINT))
            var filled = false
            for (asking in 0 until INVOICE_TRIES) {
                delay(INVOICE_MS)
                if (paid(id)) {
                    filled = true
                    break
                }
            }
            if (!filled) throw IOException("The invoice was not paid.")
        }
        say(Step.Locking)
        oneAtATime.withLock { minter().offer(pubkey, at, sale.listing, sale.price, sale.h) }
        say(Step.Waiting)
        // Until it arrives the site knows more than the phone: it is looked for whenever the guest looks.
        expect()
        // The price is locked now. Whatever goes wrong from here, it is not paid a second time:
        // the NFT arrives later, or the money comes back.
        try {
            for (look in 0 until ARRIVE_TRIES) {
                if (oneAtATime.withLock { collect(mine, at) }.any { it.h == sale.h }) return true
                // Nothing else of the guest's waits on this: a door can be answered meanwhile.
                minter().wait(pubkey, WAIT_S)
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not look for what was bought: ${e.message}")
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Looking for what was bought took too long")
        }
        return false
    }

    /** What happens while something is bought, for a screen to say. */
    sealed interface Step {
        /** The wallet is short: this invoice fills it at `mint`. Test money pays its own invoices. */
        data class Pay(val invoice: String, val sats: Long, val mint: String, val test: Boolean) : Step
        data object Locking : Step
        data object Waiting : Step
    }

    /**
     * Puts one of the guest's NFTs up for sale at a price, or changes its price. It is for sale
     * when the market lists it: one the market would not take is not left looking as if it were.
     */
    suspend fun ask(card: String, price: Long) = oneAtATime.withLock {
        val mine = me ?: throw IOException(app.getString(R.string.guest_not_ours))
        val before = asks
        save(before + (card to price))
        val done = try {
            round(mine)
        } catch (e: IOException) {
            save(before)
            throw e
        }
        val listed = (done.get("listings") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty().any { it.get("card").text() == card }
        if (!listed && card in asks) {
            save(before - card)
            val why = (done.get("problems") as? JsonArray)?.firstNotNullOfOrNull { it.text() }
            throw IOException(why ?: app.getString(R.string.guest_sell_refused))
        }
    }

    /** Takes one of the guest's NFTs off sale. It stays for sale, and says so, if the market could not be told. */
    suspend fun unask(card: String) = oneAtATime.withLock {
        val mine = me ?: return@withLock
        val before = asks
        save(before - card)
        try {
            round(mine)
        } catch (e: IOException) {
            save(before)
            throw e
        }
        Unit
    }

    /**
     * One round of the guest's own selling: what they have put up is on sale at their price, and
     * an offer that meets it is taken. Returns what sold, by card. Does nothing for a guest who
     * is selling nothing and has nothing out.
     */
    suspend fun tend(): List<String> = oneAtATime.withLock {
        val mine = me ?: return@withLock emptyList()
        (round(mine).get("sold") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty().mapNotNull { it.get("card").text() }
    }

    /** One round of the guest's shop, for a caller that holds the lock: what the page did. What sold is no longer for sale. */
    private suspend fun round(mine: Collection): JsonObject {
        val wanted = JsonArray().apply {
            for ((card, price) in asks) {
                add(JsonObject().apply {
                    addProperty("id", card)
                    addProperty("price", price)
                })
            }
        }
        val done = minter().tend(mine.pubkey, mint, wanted.toString(), "all")
        val sold = (done.get("sold") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty().mapNotNull { it.get("card").text() }
        if (sold.isNotEmpty()) save(asks - sold.toSet())
        return done
    }

    /** Waits for news from the market for the guest, for up to half a minute: whether there was any. */
    suspend fun news(): Boolean {
        val mine = me ?: return false.also { delay(WAIT_S * 1000L) }
        return minter().wait(mine.pubkey, WAIT_S)
    }

    private fun save(asks: Map<String, Long>) {
        val kept = JsonObject().apply { for ((card, price) in asks) addProperty(card, price) }
        prefs.edit().putString(ASKS, kept.toString()).apply()
    }

    /** As [ensure], for a caller that already holds the lock. */
    private suspend fun ensureLocked(): Collection = me ?: run {
        val site = Config(app).site
        val called = name.ifEmpty { UNNAMED }
        val made = Collection.parse("$site/p/${minter().create(called)}")
            ?: throw IOException("The site made no collection")
        prefs.edit().putString(ME, made.url).putString(TOLD, called).apply()
        made
    }

    /**
     * An NFT for sale, by the site's listing of it, or null if it is not for sale any more.
     * Throws when the site could not be asked: that is not the same as its having been sold.
     */
    suspend fun listing(id: String, mint: String? = null): ForSale? = withContext(Dispatchers.IO) {
        val site = Config(app).site
        HTTP.newCall(Request.Builder().url("$site/api/market/listings/$id").build()).execute().use { answer ->
            if (answer.code == 404) return@use null
            if (!answer.isSuccessful) throw IOException(app.getString(R.string.guest_slow))
            val listed = try {
                JsonParser.parseString(answer.body?.string().orEmpty()) as? JsonObject
            } catch (e: JsonParseException) {
                null
            } ?: throw IOException(app.getString(R.string.guest_slow))
            listed.takeIf { it.get("state").text() == "active" }?.let { forSale(it, mint) }
        }
    }

    /**
     * A collection as somebody looking through it sees it: every NFT its page lists, and which
     * of them are for sale. The list is kept, so that an NFT opened from it can be described.
     * Throws when the site could not be asked.
     */
    suspend fun browse(event: Collection): Browsed = withContext(Dispatchers.IO) {
        val theirs = json("${event.site}/api/profiles/${event.pubkey}") ?: throw IOException(app.getString(R.string.guest_slow))
        (theirs.get("cards") as? JsonArray)?.let { Tickets(app, event).save(it) }
        val listed = Tickets(app, event).all
        val issued = listed.map { it.h }.toSet()
        val selling = HashMap<String, ForSale>()
        try {
            for (page in 0 until PAGES) {
                val market = json("${event.site}/api/market/listings?sort=price_asc&limit=60&offset=${page * 60}")
                for (item in (market?.get("items") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()) {
                    val active = item.get("state").text().let { it == null || it == "active" }
                    val sale = forSale(item, null)?.takeIf { active && it.h in issued && it.seller != me?.pubkey } ?: continue
                    // Cheapest first, so the first seen of an NFT is its lowest price.
                    selling.putIfAbsent(sale.h, sale)
                }
                if (market?.get("more")?.takeIf { it.isJsonPrimitive }?.asBoolean != true) break
            }
        } catch (e: JsonParseException) {
            Log.e(TAG, "The market did not answer as expected")
        }
        Browsed(theirs.get("name").text().orEmpty(), listed, selling)
    }

    private fun forSale(listed: JsonObject, mint: String?): ForSale? {
        return ForSale(
            listed.get("id").text() ?: return null, listed.get("h").text() ?: return null,
            listed.get("title").text().orEmpty(), listed.get("price").whole() ?: return null,
            listed.get("seller").text().orEmpty(), listed.get("seller_name").text().orEmpty(), mint,
        )
    }

    private fun json(url: String): JsonObject? = HTTP.newCall(Request.Builder().url(url).build()).execute().use {
        if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
        JsonParser.parseString(it.body?.string().orEmpty()) as? JsonObject
    }

    companion object {
        private const val TAG = "Guest"
        private const val ME = "me"
        private const val NAME = "name"
        private const val TOLD = "told"
        private const val MINT = "mint"
        private const val ASKS = "asks"
        private const val TAKEN_OUT = "taken_out"
        private const val HAD = "had"
        private const val AWAITING = "awaiting"
        private const val DOORS = "doors"
        private const val EVENTS = "events"
        private const val VIEWING = "viewing"
        private const val PURSE = "purse"
        private const val BURN_AT_ONCE = 4

        // How long a seller has to answer before the money comes back, or a link to be opened, with room to spare.
        private const val AWAIT_MS = 36 * 60 * 60_000L

        /** What a guest who has given no name is called on the site. No door greets by it. */
        const val UNNAMED = "Guest"
        private const val WAIT_S = 25
        private const val ASK_MS = 8000L
        private const val INVOICE_MS = 1500L
        private const val INVOICE_TRIES = 120
        private const val ARRIVE_TRIES = 6
        private const val PAGES = 5
        private val HTTP = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    }
}
