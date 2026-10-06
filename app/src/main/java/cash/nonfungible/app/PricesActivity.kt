package cash.nonfungible.app

import android.app.DatePickerDialog
import android.os.Bundle
import android.text.format.DateUtils
import android.util.Log
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cash.nonfungible.app.databinding.ActivityPricesBinding
import cash.nonfungible.app.databinding.ItemChangeBinding
import cash.nonfungible.app.databinding.RowCollectionBinding
import cash.nonfungible.app.databinding.RowPriceBinding
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.text
import cash.nonfungible.app.studio.Made
import cash.nonfungible.app.studio.Plan
import cash.nonfungible.app.studio.Prices
import cash.nonfungible.app.studio.Resale
import cash.nonfungible.app.studio.Shop
import cash.nonfungible.app.studio.Step
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.text.NumberFormat
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * What a collection's NFTs cost: a price for each kind today, the days from which the prices
 * are others, and where the collection is paid. Saving puts the plan to work.
 */
class PricesActivity : AppCompatActivity() {

    private lateinit var binding: ActivityPricesBinding
    private lateinit var collection: Collection
    private val app get() = application as App
    private val prices by lazy { Prices(this, collection) }
    private val kinds by lazy { Made(this, collection).kinds.map { it.label } }
    private var mint = Prices.TEST_MINT
    private var unit = Prices.SATS
    private val units = ArrayList<TextView>()

    /** The prices of today, and of each change to come, as the form holds them. */
    private val today = LinkedHashMap<String, EditText>()

    private class Change(var from: Long, val fields: Map<String, EditText>)

    private val changes = ArrayList<Change>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        collection = intent.getStringExtra(EXTRA_COLLECTION)?.let(Collection::parse) ?: return finish()
        binding = ActivityPricesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        val host = collection.site.substringAfter("://")
        val plan = prices.plan.settled(now())
        mint = plan.mint
        unit = plan.unit
        binding.selling.text = getString(R.string.prices_selling, host)
        binding.selling.isChecked = plan.selling
        binding.byItself.isChecked = plan.byItself
        today += rows(binding.now, plan.now)
        showUnits()
        for (step in plan.later.sortedBy { it.from }) show(step)
        binding.add.setOnClickListener {
            // A month on from the last change, at the prices before it, to be put up or down.
            val last = changes.maxOfOrNull { it.from } ?: now()
            show(Step(midnight(last + MONTH), (changes.lastOrNull()?.fields ?: today).mapValues { amount(it.value) }))
        }
        binding.save.setOnClickListener { save() }
        binding.cashOut.setOnClickListener { cashOut() }
        showMints(listOf(mint to getString(R.string.prices_test_mint).takeIf { mint == Prices.TEST_MINT }.orEmpty()))
        lifecycleScope.launch { showMints(withContext(Dispatchers.IO) { suggested() }) }
        lifecycleScope.launch { showResold(app.sales.resales(collection)) }
        // What the collection has been paid is there to take out whether or not it is selling now.
        lifecycleScope.launch { app.sales.count(collection) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.sales.shops.collect { shops -> report(shops[collection.url]) }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        app.sales.wake()
    }

    /** A price field for every kind, filled with what the kind costs. */
    private fun rows(into: android.view.ViewGroup, costs: Map<String, Long>): Map<String, EditText> =
        kinds.associateWith { kind ->
            val row = RowPriceBinding.inflate(layoutInflater, into, true)
            row.kind.text = kind
            row.price.setText(costs[kind]?.takeIf { it > 0 }?.toString().orEmpty())
            row.unit.text = unit
            units += row.unit
            row.price
        }

    private fun show(step: Step) {
        val block = ItemChangeBinding.inflate(layoutInflater, binding.later, true)
        val change = Change(step.from, rows(block.rows, step.prices))
        changes += change
        fun day() {
            block.day.text = getString(R.string.prices_from, date(change.from))
        }
        day()
        block.day.setOnClickListener {
            val at = Calendar.getInstance().apply { timeInMillis = change.from * 1000 }
            DatePickerDialog(this, { _, year, month, dayOfMonth ->
                change.from = Calendar.getInstance().apply { clear(); set(year, month, dayOfMonth) }.timeInMillis / 1000
                day()
            }, at[Calendar.YEAR], at[Calendar.MONTH], at[Calendar.DAY_OF_MONTH]).show()
        }
        // A change can be brought forward by hand: its prices become today's.
        block.apply.setOnClickListener {
            for ((kind, field) in change.fields) today[kind]?.setText(field.text)
            changes -= change
            binding.later.removeView(block.root)
        }
        block.remove.setOnClickListener {
            changes -= change
            binding.later.removeView(block.root)
        }
    }

    /** Sats, or a currency: whichever is chosen is written beside every price. */
    private fun showUnits() {
        binding.units.removeAllViews()
        for (one in Prices.UNITS) {
            val pill = layoutInflater.inflate(R.layout.pill, binding.units, false) as TextView
            pill.text = one
            pill.isSelected = one == unit
            pill.setOnClickListener {
                unit = one
                showUnits()
            }
            binding.units.addView(pill)
        }
        for (shown in units) shown.text = unit
    }

    private fun showMints(mints: List<Pair<String, String>>) {
        if (mints.isEmpty()) return
        binding.mints.removeAllViews()
        for ((url, name) in mints) {
            val pill = layoutInflater.inflate(R.layout.pill, binding.mints, false) as TextView
            pill.text = name.ifEmpty { url.substringAfter("://") }
            pill.isSelected = url == mint
            pill.setOnClickListener {
                mint = url
                showMints(mints)
            }
            binding.mints.addView(pill)
        }
    }

    /** What holders are selling on, each with a way for the collection to have it back. */
    private fun showResold(resold: List<Resale>) {
        binding.resoldTitle.isVisible = resold.isNotEmpty()
        binding.resold.removeAllViews()
        for (one in resold) {
            val row = RowCollectionBinding.inflate(layoutInflater, binding.resold, true)
            row.name.text = one.title
            row.count.text = getString(R.string.prices_resold_by, sats(one.price), one.seller)
            row.chosen.setText(R.string.prices_buy_back)
            row.chosen.setOnClickListener {
                row.chosen.isEnabled = false
                lifecycleScope.launch {
                    val refused = app.sales.buyBack(collection, one)
                    row.count.text = refused?.let { getString(R.string.prices_offer_failed, it) }
                        ?: getString(R.string.prices_offered, one.seller)
                    row.chosen.isVisible = refused != null
                    row.chosen.isEnabled = true
                }
            }
        }
    }

    /** The mints the site suggests being paid at, and the one already chosen. */
    private fun suggested(): List<Pair<String, String>> = try {
        HTTP.newCall(Request.Builder().url("${collection.site}/api/market/config").build()).execute().use { answer ->
            val config = JsonParser.parseString(answer.body?.string().orEmpty()) as? JsonObject
            val shortcuts = (config?.get("mint_shortcuts") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()
                .mapNotNull { one -> one.get("url").text()?.let { it to one.get("name").text().orEmpty() } }
            val local = (config?.get("dev_mints") as? JsonArray)?.mapNotNull { it.text() }.orEmpty()
                .map { it to getString(R.string.prices_dev_mint) }
            (local + shortcuts).let { known -> if (known.any { it.first == mint }) known else known + (mint to "") }
        }
    } catch (e: IOException) {
        Log.e(TAG, "Could not ask the site where to be paid: ${e.message}")
        emptyList()
    } catch (e: JsonParseException) {
        emptyList()
    }

    private fun save() {
        val plan = Plan(
            selling = binding.selling.isChecked,
            mint = mint,
            unit = unit,
            byItself = binding.byItself.isChecked,
            now = today.mapValues { amount(it.value) },
            later = changes.map { change -> Step(change.from, change.fields.mapValues { amount(it.value) }) },
        )
        val was = prices.plan
        prices.plan = plan
        binding.save.isEnabled = false
        binding.status.setText(if (plan.selling) R.string.prices_opening else R.string.prices_saved)
        lifecycleScope.launch {
            // What is still out when a shop closes is said: an NFT nobody is selling would take a buyer's money and keep it a day.
            val left = if (plan.selling) app.sales.round(collection).problem else if (was.selling) app.sales.close(collection) else null
            binding.save.isEnabled = true
            when {
                left != null -> binding.status.text = getString(R.string.prices_problem, left)
                plan.selling -> report(app.sales.shops.value[collection.url])
                else -> binding.status.setText(R.string.prices_saved)
            }
        }
    }

    /** How the shop stands, in a line: what is out, what has sold, and the change to come. */
    private fun report(shop: Shop?) {
        val (sold, earned) = prices.sold
        val plan = prices.plan
        val said = listOfNotNull(
            shop?.takeIf { plan.selling }?.let { resources.getQuantityString(R.plurals.prices_on_sale, it.onSale.size, it.onSale.size) },
            getString(R.string.prices_sold, sold, sats(earned)).takeIf { sold > 0 },
            shop?.waiting?.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.prices_waiting, it, it) },
            plan.next(now())?.let { getString(R.string.prices_next, date(it.from)) },
        ).joinToString(" · ")
        // How a move of its earnings went is said first, and stays said: the shop reports again and again.
        binding.status.text = listOfNotNull(moved, said.ifEmpty { null }, shop?.problem).joinToString("\n")
        val balance = shop?.balance ?: 0
        // Money taken out and not yet put anywhere comes first: it exists only as that token.
        val taken = prices.takenOut
        binding.cashOut.isVisible = balance > 0 || taken != null
        binding.cashOut.text = if (taken != null) getString(R.string.wallet_move)
        else getString(R.string.prices_cash_out, sats(balance))
    }

    /** How the last move of its earnings went, while this screen is up. */
    private var moved: String? = null

    /** Brings what the collection has been paid into the wallet of the account that made it. */
    private fun cashOut() {
        binding.cashOut.isEnabled = false
        binding.cashOut.setText(R.string.wallet_moving)
        lifecycleScope.launch {
            val saved = app.collections.all.firstOrNull { it.collection == collection } ?: Saved(collection, "")
            val said = try {
                getString(R.string.wallet_moved, sats(app.earned(saved)), saved.name)
            } catch (e: IOException) {
                e.message
            }
            moved = said
            app.sales.count(collection)
            binding.cashOut.isEnabled = true
            report(app.sales.shops.value[collection.url])
        }
    }

    private fun amount(field: EditText): Long = field.text.toString().toLongOrNull() ?: 0

    private fun now(): Long = System.currentTimeMillis() / 1000

    private fun midnight(time: Long): Long = Calendar.getInstance().apply {
        timeInMillis = time * 1000
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis / 1000

    private fun date(time: Long): String = DateUtils.formatDateTime(
        this, time * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL
    )

    companion object {
        const val EXTRA_COLLECTION = "collection"
        private const val TAG = "PricesActivity"
        private const val MONTH = 30L * 24 * 60 * 60
        private val HTTP = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()

        /** An amount of sats as people write one. */
        fun sats(amount: Long): String = NumberFormat.getIntegerInstance().format(amount)
    }
}
