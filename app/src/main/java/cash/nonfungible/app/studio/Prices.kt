package cash.nonfungible.app.studio

import android.content.Context
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.text
import cash.nonfungible.app.entry.whole
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser

/** A day from which a collection's NFTs cost something else: sats for each kind, by its name. */
data class Step(val from: Long, val prices: Map<String, Long>)

/**
 * What a collection charges: where it is paid, the prices now, and the changes to come.
 * Prices are whole amounts of `unit`: sats, or a currency an event thinks in, which is turned
 * into sats at the going rate each time something is put on sale.
 */
data class Plan(
    val selling: Boolean = false,
    val mint: String = Prices.TEST_MINT,
    val unit: String = Prices.SATS,
    val byItself: Boolean = true,
    val now: Map<String, Long> = emptyMap(),
    val later: List<Step> = emptyList(),
) {
    /** The prices on a day: those of the last change that has come, when prices change by themselves. */
    fun at(time: Long): Map<String, Long> =
        later.sortedBy { it.from }.lastOrNull { byItself && it.from <= time }?.prices ?: now

    /** The next change still to come. */
    fun next(time: Long): Step? = later.sortedBy { it.from }.firstOrNull { it.from > time }

    /** The plan once every change that has come is the price now, and only those to come are left. */
    fun settled(time: Long): Plan =
        if (!byItself) this else copy(now = at(time), later = later.filter { it.from > time })
}

/**
 * What the phone remembers of a collection's prices. A kind with no price, or a price of
 * nothing, is not for sale. Times are seconds since the epoch.
 */
class Prices(private val context: Context, collection: Collection) {
    private val name = "prices-${collection.key}"
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    var plan: Plan
        get() = try {
            (JsonParser.parseString(prefs.getString(PLAN, null) ?: "{}") as? JsonObject)?.let { kept ->
                Plan(
                    selling = kept.get("selling")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false,
                    mint = kept.get("mint").text() ?: TEST_MINT,
                    unit = kept.get("unit").text()?.takeIf { it in UNITS } ?: SATS,
                    byItself = kept.get("by_itself")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                    now = prices(kept.get("now") as? JsonObject),
                    later = (kept.get("later") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
                        .mapNotNull { step ->
                            step.get("from").whole()?.let { Step(it, prices(step.get("prices") as? JsonObject)) }
                        },
                )
            } ?: Plan()
        } catch (e: JsonParseException) {
            Plan()
        }
        set(plan) {
            val kept = JsonObject().apply {
                addProperty("selling", plan.selling)
                addProperty("mint", plan.mint)
                addProperty("unit", plan.unit)
                addProperty("by_itself", plan.byItself)
                add("now", written(plan.now))
                add("later", JsonArray().apply {
                    for (step in plan.later.sortedBy { it.from }) {
                        add(JsonObject().apply {
                            addProperty("from", step.from)
                            add("prices", written(step.prices))
                        })
                    }
                })
            }
            prefs.edit().putString(PLAN, kept.toString()).apply()
        }

    /** How many NFTs the collection has sold from this phone, and for how many sats in all. */
    val sold: Pair<Int, Long> get() = prefs.getInt(SOLD, 0) to prefs.getLong(EARNED, 0)

    /**
     * Money taken out of the collection's wallet as an ecash token, until its owner has put
     * it somewhere: taking it out is done once, and the token is all there is of it.
     */
    var takenOut: String?
        get() = prefs.getString(TAKEN_OUT, null)
        set(token) {
            // Written before anything else can happen: for a while this is the only copy of the money.
            prefs.edit().putString(TAKEN_OUT, token).commit()
        }

    fun record(price: Long) {
        prefs.edit().putInt(SOLD, sold.first + 1).putLong(EARNED, sold.second + price).apply()
    }

    fun delete() {
        context.deleteSharedPreferences(name)
    }

    private fun prices(kept: JsonObject?): Map<String, Long> =
        kept?.entrySet().orEmpty().mapNotNull { (kind, price) -> price.whole()?.takeIf { it > 0 }?.let { kind to it } }
            .toMap()

    private fun written(prices: Map<String, Long>) = JsonObject().apply {
        for ((kind, price) in prices) if (price > 0) addProperty(kind, price)
    }

    companion object {
        /** Where the site suggests being paid in sats that are worth nothing, for trying things. */
        const val TEST_MINT = "https://testnut.cashu.space"

        /** What a price can be written in: sats, or a currency turned into sats when it is asked. */
        const val SATS = "sats"
        val UNITS = listOf(SATS, "USD", "EUR")

        /**
         * An amount of a currency in sats, at a rate of that currency for one bitcoin, rounded
         * to three figures so that a price does not twitch with every tick of the rate.
         */
        fun inSats(amount: Long, rate: Double): Long {
            val exact = amount * 100_000_000.0 / rate
            val step = Math.pow(10.0, Math.floor(Math.log10(exact)) - 2).coerceAtLeast(1.0)
            return (Math.round(exact / step) * step).toLong()
        }
        private const val PLAN = "plan"
        private const val SOLD = "sold"
        private const val EARNED = "earned"
        private const val TAKEN_OUT = "taken_out"
    }
}
