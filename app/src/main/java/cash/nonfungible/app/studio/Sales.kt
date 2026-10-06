package cash.nonfungible.app.studio

import android.content.Context
import android.util.Log
import cash.nonfungible.app.R
import cash.nonfungible.app.App
import cash.nonfungible.app.entry.Admitted
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Tickets
import cash.nonfungible.app.entry.text
import cash.nonfungible.app.entry.whole
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** How a collection's shop stood after the last round: what is out, what came in, what went wrong. */
data class Shop(
    val onSale: Map<String, Listed> = emptyMap(),
    val balance: Long = 0,
    val waiting: Int = 0,
    val problem: String? = null,
)

/** How deleting a collection went: how many NFTs were burned, how many it still holds, and why. */
data class Destroyed(val burned: Int, val left: Int, val problem: String?)

/** An NFT on sale: the site's listing of it and its price. */
data class Listed(val listing: String, val price: Long)

/** An NFT of a collection that whoever holds it now is selling on: the site's listing of it. */
data class Resale(val listing: String, val h: String, val title: String, val price: Long, val seller: String)

/** An NFT that was just paid for: which, for how much, and what its buyer's collection is called. */
data class Sold(val collection: Collection, val card: String, val h: String, val price: Long, val buyer: String)

/**
 * Keeps shop for the collections this phone made, for as long as the app is running. Every
 * round it puts a few NFTs of each priced kind on sale at today's price, brings what is on sale
 * to that price when a change has come, takes the offers that pay it, and collects the money.
 * A sale is the site's own: the buyer's ecash is locked for the seller and released only
 * against the NFT, so paying and receiving happen together or not at all.
 */
class Sales(private val app: App) {
    /** How each collection's shop stands, by the collection's address. */
    val shops = MutableStateFlow<Map<String, Shop>>(emptyMap())

    /** Every sale as it happens, for whichever screen is looking. */
    val sold = MutableSharedFlow<Sold>(extraBufferCapacity = 16)

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val pages = HashMap<String, Minter>()
    private val oneAtATime = Mutex()
    private var keeping: Job? = null

    /** The NFTs a guest at the door is paying for right now, by their collection's address. */
    private val atTheDoor = HashMap<String, MutableSet<String>>()
    private val quoted = HashMap<String, Long>()
    private var rates = emptyMap<String, Double>()
    private var ratesAt = 0L

    /**
     * Starts keeping shop for every collection that is selling, if it is not kept already. Any
     * hosting screen calls this when it shows; a collection that starts or stops selling is
     * picked up within the half minute.
     */
    fun wake() {
        if (keeping?.isActive == true) return
        keeping = work.launch {
            val watched = HashMap<String, Job>()
            while (true) {
                val selling = app.everyCollection.map { it.collection }
                    .filter { Prices(app, it).plan.selling }
                for (collection in selling) {
                    if (watched[collection.url]?.isActive != true) watched[collection.url] = launch { watch(collection) }
                }
                for ((url, watcher) in watched) if (selling.none { it.url == url }) watcher.cancel()
                delay(LOOK_MS)
            }
        }
    }

    /**
     * Keeps one collection's shop until cancelled: a round now, and another whenever the market
     * has news for it, a price change has come, or a while has passed. The site holds the
     * question "any news?" open, so an offer is taken within a second or two of being made
     * while the phone asks only twice a minute.
     */
    suspend fun watch(collection: Collection, now: Boolean = true) {
        var last = if (now) 0L else System.currentTimeMillis() / 1000
        var first = now
        while (true) {
            val due = prices(collection).next(last)?.let { it.from <= System.currentTimeMillis() / 1000 } == true
            val asked = System.currentTimeMillis()
            val news = try {
                first || due || page(collection).wait(collection.pubkey, WAIT_S)
                    // A site that answers "nothing" at once is not asked again in the same breath.
                    .also { heard -> if (!heard && System.currentTimeMillis() - asked < 1000) delay(BREATH_MS) }
            } catch (e: IOException) {
                // The site may be busy or away. It is asked again after a breath, not at once.
                delay(BREATH_MS)
                false
            } catch (e: TimeoutCancellationException) {
                false
            }
            if (news || System.currentTimeMillis() - last * 1000 > IDLE_MS) {
                first = false
                // A round that failed is run again soon: its news is not spent.
                if (round(collection).problem == null) last = System.currentTimeMillis() / 1000 else delay(BREATH_MS)
            }
        }
    }

    private fun prices(collection: Collection): Plan = Prices(app, collection).plan

    /** Puts one NFT on sale for a guest at the door, and keeps its shop until the caller is cancelled. */
    suspend fun sellAtTheDoor(collection: Collection, card: String, listed: (Listed?, String?) -> Unit) {
        atTheDoor.getOrPut(collection.url) { HashSet() } += card
        try {
            val shop = round(collection)
            listed(shop.onSale[card], shop.problem)
            // A shop that is kept anyway hears of the offer by itself. One that is not is kept for this.
            if (card in shop.onSale) {
                if (prices(collection).selling) awaitCancellation() else watch(collection, now = false)
            }
        } finally {
            work.launch {
                // A guest may have paid as the sheet closed: one more round takes their money
                // while it is still for sale. Then, sold or not, it is no longer at the door,
                // and what did not sell comes in.
                round(collection)
                atTheDoor[collection.url]?.remove(card)
                round(collection)
            }
        }
    }

    /** A link that gives an NFT away. No round runs meanwhile, so nothing puts it on sale as it is given. */
    suspend fun gift(collection: Collection, card: String): String = oneAtATime.withLock {
        atTheDoor[collection.url]?.remove(card)
        // The page takes it off sale before it makes the link. Whoever asked may stop waiting;
        // the link is made all the same, and nothing else is begun until it is.
        withContext(NonCancellable) { page(collection).link(collection.pubkey, card) }.also {
            // The shelf has a place free: the next round fills it.
            if (prices(collection).selling) work.launch { round(collection) }
        }
    }

    /**
     * Waits for a gift link to be taken: whether somebody has the NFT now, rather than its sender
     * having taken it back. Only the link's name is asked about; its key never leaves the phone.
     */
    suspend fun taken(link: String): Boolean = withContext(Dispatchers.IO) {
        val about = Request.Builder().url(link.substringBefore('#').replace("/claim/", "/api/links/")).build()
        var status: String? = "open"
        while (status != "claimed" && status != "void") {
            delay(TAKEN_MS)
            status = try {
                HTTP.newCall(about).execute().use { answer ->
                    (JsonParser.parseString(answer.body?.string().orEmpty()) as? JsonObject)?.get("status").text()
                }
            } catch (e: IOException) {
                null
            } catch (e: JsonParseException) {
                null
            }
        }
        status == "claimed"
    }

    /**
     * One round for one collection, now: what is wanted on sale is the few of each priced kind
     * a selling collection keeps out, and whatever a guest at the door is paying for.
     */
    suspend fun round(collection: Collection): Shop = oneAtATime.withLock {
        val prices = Prices(app, collection)
        val shop = try {
            app.sync(collection)
            // The plan as it is now, not as it was before the page was read: it may have been saved since.
            val plan = prices.plan
            val door = atTheDoor[collection.url].orEmpty().toList()
            val rate = rate(plan.unit)
            val wanted = wanted(app, collection, plan, door) { amount -> asked(collection, plan.unit, amount, rate) }
            // A plan that runs by itself sells whatever it has out. One that does not sells only
            // to somebody waiting at the door.
            val accept = if (plan.byItself) "all" else JsonArray().apply { door.forEach(::add) }.toString()
            // A round that has begun is finished, whatever becomes of whoever asked for it: two
            // rounds under way together would each list what the other took in.
            val done = withContext(NonCancellable) { page(collection).tend(collection.pubkey, plan.mint, wanted, accept) }
            val sales = (done.get("sold") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
            for (sale in sales) {
                val price = sale.get("price").whole() ?: 0
                prices.record(price)
                atTheDoor[collection.url]?.remove(sale.get("card").text())
                sold.tryEmit(Sold(collection, sale.get("card").text().orEmpty(), sale.get("h").text().orEmpty(), price, sale.get("buyer").text().orEmpty()))
            }
            if (sales.isNotEmpty()) app.sync(collection)
            val listed = (done.get("listings") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
                .mapNotNull { one ->
                    val card = one.get("card").text() ?: return@mapNotNull null
                    card to Listed(one.get("id").text().orEmpty(), one.get("price").whole() ?: 0)
                }.toMap()
            val problems = (done.get("problems") as? JsonArray)?.mapNotNull { it.text() }.orEmpty()
            Shop(listed, done.get("balance").whole() ?: 0, done.get("waiting").whole()?.toInt() ?: 0, problems.firstOrNull())
        } catch (e: IOException) {
            Log.e(TAG, "A round of keeping shop failed: ${e.message}")
            (shops.value[collection.url] ?: Shop()).copy(problem = e.message)
        } catch (e: TimeoutCancellationException) {
            (shops.value[collection.url] ?: Shop()).copy(problem = e.message)
        }
        shops.value = shops.value + (collection.url to shop)
        shop
    }

    private fun page(collection: Collection): Minter = pages.getOrPut(collection.site) { Minter(app, collection.site) }

    /** Takes everything a collection has for sale off sale. Returns why not, when some of it is still out. */
    suspend fun close(collection: Collection): String? = oneAtATime.withLock {
        try {
            withContext(NonCancellable) { page(collection).unlist(collection.pubkey) }
            // What it was paid is still its own.
            shops.value = shops.value + (collection.url to Shop(balance = shops.value[collection.url]?.balance ?: 0))
            null
        } catch (e: IOException) {
            Log.e(TAG, "Could not take a collection off sale: ${e.message}")
            e.message.orEmpty()
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Could not take a collection off sale: ${e.message}")
            app.getString(R.string.guest_slow)
        }
    }

    /**
     * Deletes a collection this phone minted, as far as it can be: everything it has for sale
     * comes off sale, and every NFT it still holds is burned at the mint and dropped from the
     * site with its picture. NFTs it has sent or sold are their owners' and are not touched.
     * `offSale` and `say` hear how far it has got.
     *
     * Throws before anything is burned if the collection holds more than `lose` sats, the sum
     * its owner was shown and agreed to lose with it, or ecash taken out of it and not yet put
     * anywhere unless they agreed to lose that `tokenToo`; or if something of it cannot be taken
     * off sale.
     *
     * The site has no way to remove a collection. Once nothing is left under it, it is called
     * [GONE] there, and only then does the phone forget its key: if the site cannot be told,
     * this throws and the key is kept, to tell it the next time.
     */
    suspend fun destroy(
        collection: Collection,
        offSale: (taken: Int) -> Unit,
        say: (burned: Int, of: Int) -> Unit,
        lose: Long = 0,
        tokenToo: Boolean = false,
    ): Destroyed = oneAtATime.withLock {
        val prices = Prices(app, collection)
        val mine = page(collection)
        // Sats at any mint, spendable or tied up in a sale, would be lost with the collection.
        if ((prices.takenOut != null && !tokenToo) || mine.wealth(collection.pubkey) > lose) {
            throw IOException(app.getString(R.string.delete_money))
        }
        // It stops selling for good: no round puts anything out again while it is burned.
        prices.plan = prices.plan.copy(selling = false)
        withContext(NonCancellable) { mine.unlist(collection.pubkey, offSale) }
        shops.value = shops.value - collection.url
        // What is burned is what the site lists now, not what the phone remembers.
        app.sync(collection) ?: throw IOException(app.getString(R.string.guest_slow))
        val held = Tickets(app, collection).all.filter { !it.sent && it.id.isNotEmpty() }
        var burned = 0
        var problem: String? = null
        say(0, held.size)
        // A few at a time, so that there is something to say. The page keeps to the site's pace.
        for (some in held.chunked(BURN_AT_ONCE)) {
            val ids = JsonArray().apply { some.forEach { add(it.id) } }.toString()
            val done = withContext(NonCancellable) { mine.patient("burn", collection.pubkey, ids).asJsonObject }
            burned += done.get("burned").whole()?.toInt() ?: 0
            problem = problem ?: (done.get("problems") as? JsonArray)?.firstNotNullOfOrNull { it.text() }
            say(burned, held.size)
        }
        // Done only when the site says so: a page that could not be read is not an empty one.
        app.sync(collection) ?: throw IOException(app.getString(R.string.delete_unsure, burned))
        val left = Tickets(app, collection).all.count { !it.sent }
        if (left == 0) {
            // Counted once more: burning takes a while, and something may have been paid meanwhile.
            if (mine.wealth(collection.pubkey) > lose) throw IOException(app.getString(R.string.delete_all_more))
            // Told before the key is forgotten: nobody could tell it afterwards.
            mine.call("rename", collection.pubkey, GONE)
            mine.call("forget", collection.pubkey)
        }
        Destroyed(burned, left, problem)
    }

    /**
     * What a collection holds: `mints`, each `{name, url, test, available, locked, pending}`, and
     * `settling`, how many payments it has begun that the mint has not finished.
     */
    suspend fun purse(collection: Collection): JsonObject = oneAtATime.withLock {
        page(collection).call("money", collection.pubkey).asJsonObject
    }

    /**
     * Whether the site has a page for a key: yes, no, or null when it could not be asked. Not
     * being able to ask is not the same as there being none.
     */
    suspend fun exists(collection: Collection): Boolean? = withContext(Dispatchers.IO) {
        try {
            HTTP.newCall(Request.Builder().url("${collection.site}/api/profiles/${collection.pubkey}").build()).execute().use { answer ->
                when {
                    answer.isSuccessful -> true
                    answer.code == 404 -> false
                    else -> null
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    /** The keys the site's page holds on this phone, by the collections they are the keys of. */
    suspend fun held(site: String): List<String> = pages.getOrPut(site) { Minter(app, site) }.held()

    /** Takes a collection that is being removed off sale, without anybody waiting for it. */
    fun forget(collection: Collection) {
        work.launch { close(collection) }
    }

    /** Asks what a collection has been paid, for a screen that shows it before any round has run. */
    suspend fun count(collection: Collection) = oneAtATime.withLock {
        try {
            // Collected first, as `balance` does; then everything it can spend, at whatever mint it was paid.
            page(collection).balance(collection.pubkey, prices(collection).mint)
            val balance = (page(collection).call("money", collection.pubkey).asJsonObject.get("mints") as? JsonArray)
                ?.filterIsInstance<JsonObject>().orEmpty().sumOf { it.get("available").whole() ?: 0 }
            shops.value = shops.value + (collection.url to (shops.value[collection.url] ?: Shop()).copy(balance = balance))
        } catch (e: IOException) {
            Log.e(TAG, "Could not count a collection's money: ${e.message}")
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Counting a collection's money took too long")
        }
    }

    /** What one NFT of a kind costs now in sats, or null if the kind is not for sale. */
    suspend fun asking(collection: Collection, kind: String): Long? {
        val plan = prices(collection)
        val amount = plan.at(System.currentTimeMillis() / 1000)[kind] ?: return null
        return asked(collection, plan.unit, amount, rate(plan.unit))
    }

    /**
     * An amount of a plan's unit in sats. A price in a currency keeps the sats it was last
     * asked at while the rate stays within a hundredth of it, so that what is on sale is not
     * repriced for nothing. Null when the rate is not to be had.
     */
    private fun asked(collection: Collection, unit: String, amount: Long, rate: Double?): Long? {
        if (unit == Prices.SATS) return amount
        val fresh = Prices.inSats(amount, rate ?: return null)
        val key = "${collection.url} $unit $amount"
        val before = quoted[key]
        return if (before != null && Math.abs(fresh - before) * 100 < before) before else fresh.also { quoted[key] = it }
    }

    /** How much of a currency one bitcoin is, asked of mempool.space at most every ten minutes. */
    private suspend fun rate(unit: String): Double? {
        if (unit == Prices.SATS) return null
        if (System.currentTimeMillis() - ratesAt > RATE_MS) withContext(Dispatchers.IO) {
            try {
                HTTP.newCall(Request.Builder().url(RATES).build()).execute().use { answer ->
                    val said = JsonParser.parseString(answer.body?.string().orEmpty()) as? JsonObject
                    rates = Prices.UNITS.mapNotNull { one -> said?.get(one).whole()?.takeIf { it > 0 }?.let { one to it.toDouble() } }.toMap()
                    if (rates.isNotEmpty()) ratesAt = System.currentTimeMillis()
                }
            } catch (e: IOException) {
                Log.e(TAG, "No exchange rate: ${e.message}")
            } catch (e: JsonParseException) {
                Log.e(TAG, "The exchange rate did not read as one")
            }
        }
        // A rate too old to trust is no rate: nothing is priced by it.
        return rates[unit]?.takeIf { System.currentTimeMillis() - ratesAt < STALE_MS }
    }

    /**
     * The collection's NFTs that their holders have put on sale, cheapest first: a holder who
     * can no longer come sells on the site's market, where the collection can buy back like
     * anybody else.
     */
    suspend fun resales(collection: Collection): List<Resale> = withContext(Dispatchers.IO) {
        val ours = Tickets(app, collection).all.map { it.h }.toSet()
        val found = ArrayList<Resale>()
        try {
            for (page in 0 until PAGES) {
                val asked = Request.Builder().url("${collection.site}/api/market/listings?sort=new&limit=60&offset=${page * 60}")
                val listed = HTTP.newCall(asked.build()).execute().use { answer ->
                    if (!answer.isSuccessful) throw IOException("HTTP ${answer.code}")
                    JsonParser.parseString(answer.body?.string().orEmpty()) as? JsonObject
                }
                val items = (listed?.get("items") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
                for (item in items) {
                    if (item.get("h").text() !in ours || item.get("seller").text() == collection.pubkey) continue
                    found += Resale(
                        item.get("id").text() ?: continue, item.get("h").text().orEmpty(), item.get("title").text().orEmpty(),
                        item.get("price").whole() ?: continue, item.get("seller_name").text().orEmpty(),
                    )
                }
                if (listed?.get("more")?.takeIf { it.isJsonPrimitive }?.asBoolean != true) break
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not read the market: ${e.message}")
        } catch (e: JsonParseException) {
            Log.e(TAG, "The market did not answer as expected")
        }
        found.sortedBy { it.price }
    }

    /**
     * Offers a holder their asking price out of what the collection has been paid. The NFT comes
     * back when the holder accepts. Returns why not, when the offer could not be made.
     */
    suspend fun buyBack(collection: Collection, resale: Resale): String? = oneAtATime.withLock {
        try {
            // No more than the price that was shown, and only for the NFT that was shown.
            page(collection).offer(collection.pubkey, Prices(app, collection).plan.mint, resale.listing, resale.price, resale.h)
            null
        } catch (e: IOException) {
            e.message.orEmpty()
        } catch (e: TimeoutCancellationException) {
            e.message.orEmpty()
        }
    }

    /** Everything a collection has been paid, as an ecash token to take to any wallet, or null. */
    suspend fun cashOut(collection: Collection): String? = oneAtATime.withLock {
        try {
            val prices = Prices(app, collection)
            // One at a time: there is one place to keep a token, and a second would be kept over the first.
            prices.takenOut?.let { return@withLock it }
            // Where it is paid now, or wherever else it was paid before and still has something.
            val paid = prices.plan.mint
            val held = (page(collection).call("money", collection.pubkey).asJsonObject.get("mints") as? JsonArray)
                ?.filterIsInstance<JsonObject>().orEmpty().filter { (it.get("available").whole() ?: 0) > 0 }.mapNotNull { it.get("url").text() }
            val mint = held.firstOrNull { it.trimEnd('/') == paid.trimEnd('/') } ?: held.firstOrNull() ?: paid
            // Once begun this is seen through: a token nobody was waiting for would be money nobody has.
            withContext(NonCancellable) {
                val all = page(collection).balance(collection.pubkey, mint)
                if (all <= 0) null else page(collection).token(collection.pubkey, mint, all).also {
                    // The token is the money now. It is kept until its owner says they have it.
                    prices.takenOut = it
                    shops.value = shops.value + (collection.url to (shops.value[collection.url] ?: Shop()).copy(balance = 0))
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "Could not take a collection's money out: ${e.message}")
            null
        } catch (e: TimeoutCancellationException) {
            null
        }
    }

    companion object {
        private const val TAG = "Sales"

        /** What the site is told to call a collection that has been deleted: it cannot remove one. */
        const val GONE = "[deleted]"
        private const val LOOK_MS = 30_000L
        private const val RATE_MS = 10 * 60_000L
        private const val STALE_MS = 60 * 60_000L
        private const val RATES = "https://mempool.space/api/v1/prices"
        private const val WAIT_S = 25
        private const val BREATH_MS = 10_000L
        private const val TAKEN_MS = 2_000L

        // How many NFTs are burned between one word of how far it has got and the next.
        private const val BURN_AT_ONCE = 4

        // With no news, a shop is still looked over this often.
        private const val IDLE_MS = 5 * 60_000L

        // How far into the site's market a collection looks for its own NFTs.
        private const val PAGES = 5
        private val HTTP = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

        /** How many NFTs of a kind are out at once. Each costs the site a dozen requests to put out. */
        const val SHELF = 5

        /** What a collection made here calls an NFT: its kind, then "12 of 50". */
        private val TITLE = Regex("^(.*) · ([0-9]{1,6}) of [0-9]{1,6}$")

        /** The kind of an NFT made here, from what its collection lists it as. */
        fun kind(title: String): String? = TITLE.find(title)?.groupValues?.get(1)

        /**
         * Which NFTs to have on sale and for how much, as the page that keeps shop takes it:
         * the lowest-numbered few of each priced kind that the collection still holds and has
         * not let in, and whatever was asked for besides.
         */
        fun wanted(
            context: Context,
            collection: Collection,
            plan: Plan,
            also: List<String>,
            inSats: (Long) -> Long? = { it },
        ): String {
            val prices = plan.at(System.currentTimeMillis() / 1000)
            val admitted = Admitted(context, collection).all
            val held = Tickets(context, collection).all.filter { !it.sent && it.id.isNotEmpty() }
            val out = JsonArray()
            for ((kind, amount) in prices) {
                // A price in a currency with no rate to be had is not asked at all.
                val price = inSats(amount) ?: continue
                val ofKind = held.filter { kind(it.title) == kind }
                    .sortedBy { TITLE.find(it.title)?.groupValues?.get(2)?.toIntOrNull() ?: 0 }
                // What has come in is not put out for strangers. A guest at the door may be let
                // in while they pay: theirs stays for sale until they have.
                val shelf = if (plan.selling) ofKind.filter { it.h !in admitted }.take(SHELF) else emptyList()
                for (ticket in (ofKind.filter { it.id in also } + shelf).distinct()) {
                    out.add(JsonObject().apply {
                        addProperty("id", ticket.id)
                        addProperty("price", price)
                    })
                }
            }
            return out.toString()
        }
    }
}
