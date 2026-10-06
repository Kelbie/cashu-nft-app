package cash.nonfungible.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cash.nonfungible.app.databinding.ActivityTicketsBinding
import cash.nonfungible.app.databinding.RowSettingBinding
import cash.nonfungible.app.databinding.RowTicketBinding
import cash.nonfungible.app.databinding.SheetPickBinding
import cash.nonfungible.app.entry.Admission
import cash.nonfungible.app.entry.Admitted
import cash.nonfungible.app.entry.Tickets
import cash.nonfungible.app.studio.Made
import cash.nonfungible.app.studio.Minter
import cash.nonfungible.app.studio.Prices
import cash.nonfungible.app.studio.Templates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * One collection: every NFT of it, in the order of their numbers, with a tab for each type its
 * maker named and how it stands in numbers; and at the foot what can be done with it. One this
 * phone made can be sold from, sent from and added to; any can have its door opened. What is
 * done seldom (prices, minting more, its key, deleting it) is behind the dots.
 */
class TicketsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTicketsBinding
    private lateinit var saved: Saved
    private val app get() = application as App
    private val rows = Rows()
    private var showing: Job? = null

    /** The type whose tab is chosen: none for every type. */
    private var type: String? = null

    /** The count the list is narrowed to, if any. */
    private var only: Only? = null

    private enum class Only(val label: Int, val none: Int) {
        IN(R.string.tickets_in, R.string.tickets_none_admitted),
        OUT(R.string.tickets_not_in, R.string.tickets_none_waiting),
        LISTED(R.string.tickets_listed_count, R.string.tickets_none_listed),
        SOLD(R.string.tickets_sold_count, R.string.tickets_none_sold),
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTicketsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        saved = app.collections.selected ?: return finish()
        binding.title.text = saved.name
        binding.back.setOnClickListener { finish() }
        binding.list.adapter = rows
        binding.search.doAfterTextChanged { show() }
        binding.refresh.setOnRefreshListener { readAgain() }
        binding.more.setOnClickListener { more() }
    }

    override fun onStart() {
        super.onStart()
        if (!::saved.isInitialized) return
        // Deleted or removed from another screen: there is nothing left to show.
        if (app.collections.all.none { it.collection == saved.collection }) return finish()
        app.sales.wake()
        foot()
        show()
    }

    /** Whether its NFTs are tickets: what it was made as, and a collection only added is kept for its door. */
    private val tickets: Boolean
        get() = Made(this, saved.collection).template?.let { made -> Templates(this).all.firstOrNull { it.id == made }?.ticket } ?: true

    /** The two things done most, by what the collection is: sell or send from it, and open its door. */
    private fun foot() {
        val owned = app.owns(saved.collection)
        val hand = { sellOrSend(saved.collection) { readAgain() } }
        val door = {
            Config(this).doorOpen = saved.collection.url
            startActivity(Intent(this, DoorActivity::class.java))
        }
        val prices = { startActivity(Intent(this, PricesActivity::class.java).putExtra(PricesActivity.EXTRA_COLLECTION, saved.collection.url)) }
        binding.second.isVisible = owned
        when {
            // Tickets it made: sold or sent, and let in.
            owned && tickets -> {
                binding.second.setText(R.string.door_hand)
                binding.second.setOnClickListener { hand() }
                binding.first.setText(R.string.collection_open_door)
                binding.first.setOnClickListener { door() }
            }
            // Something else it made: there is no door to it.
            owned -> {
                binding.second.setText(R.string.prices_title)
                binding.second.setOnClickListener { prices() }
                binding.first.setText(R.string.door_hand)
                binding.first.setOnClickListener { hand() }
            }
            else -> {
                binding.first.setText(R.string.collection_open_door)
                binding.first.setOnClickListener { door() }
            }
        }
    }

    private fun say(what: String) {
        binding.note.text = what
        binding.note.isVisible = what.isNotEmpty()
    }

    /** What is done seldom, a row each: each says how things stand. */
    private fun more() {
        val sheet = SheetPickBinding.inflate(layoutInflater)
        val dialog = sheetOf(sheet.root)
        val collection = saved.collection
        val owned = app.owns(collection)
        sheet.title.text = saved.name
        fun row(label: Int, value: String, danger: Boolean = false, act: () -> Unit) {
            val row = RowSettingBinding.inflate(layoutInflater, sheet.rows, true)
            row.label.setText(label)
            if (danger) row.label.setTextColor(getColor(R.color.bad))
            row.value.text = value
            row.root.setOnClickListener {
                dialog.dismiss()
                act()
            }
        }
        if (owned) {
            val tally = app.tally(collection)
            row(R.string.settings_mint, resources.getQuantityString(R.plurals.collection_holds, tally.all, tally.all)) {
                startActivity(Intent(this, MakeActivity::class.java).putExtra(MakeActivity.EXTRA_COLLECTION, collection.url))
            }
            if (tickets) row(R.string.prices_title, getString(priced())) {
                startActivity(Intent(this, PricesActivity::class.java).putExtra(PricesActivity.EXTRA_COLLECTION, collection.url))
            }
            // A collection made here has its only key here.
            row(R.string.settings_key, "") {
                confirm(
                    getString(R.string.settings_key_title),
                    getString(R.string.settings_key_message, collection.site.substringAfter("://")),
                    getString(R.string.settings_key_confirm),
                ) { copyKey() }
            }
        }
        val admitted = Admitted(this, collection)
        val count = admitted.count
        if (count > 0) row(R.string.collection_clear, resources.getQuantityString(R.plurals.collection_admitted, count, count)) {
            confirm(getString(R.string.settings_clear_title), getString(R.string.settings_clear_message), getString(R.string.settings_clear_confirm), forGood = true) {
                admitted.clear()
                show()
            }
        }
        row(if (owned) R.string.delete_label else R.string.settings_remove, "", danger = true) {
            deleteCollection(saved) { finish() }
        }
        dialog.show()
    }

    /** How its prices stand, in a few words. */
    private fun priced(): Int {
        val plan = Prices(this, saved.collection).plan
        return when {
            plan.selling -> R.string.settings_prices_selling
            plan.now.values.any { it > 0 } -> R.string.settings_prices_set
            else -> R.string.settings_prices_none
        }
    }

    /** Puts the collection's key on the clipboard, marked as something not to be shown. */
    private fun copyKey() {
        val collection = saved.collection
        val page = minter ?: Minter(this, collection.site).also {
            minter = it
            binding.pages.addView(it.window)
        }
        lifecycleScope.launch {
            say(
                try {
                    val key = page.key(collection.pubkey) ?: throw IOException("this phone does not hold it")
                    val copied = ClipData.newPlainText("key", key)
                    copied.description.extras = PersistableBundle().apply { putBoolean(SettingsActivity.SENSITIVE, true) }
                    getSystemService<ClipboardManager>()?.setPrimaryClip(copied)
                    getString(R.string.settings_key_copied)
                } catch (e: IOException) {
                    getString(R.string.settings_key_failed, e.message)
                } catch (e: TimeoutCancellationException) {
                    getString(R.string.settings_key_failed, e.message)
                }
            )
        }
    }

    private var minter: Minter? = null

    override fun onDestroy() {
        minter?.close()
        super.onDestroy()
    }

    private fun show() {
        val wanted = binding.search.text.toString().trim()
        showing?.cancel()
        showing = lifecycleScope.launch {
            val admitted = Admitted(this@TicketsActivity, saved.collection).all
            val owned = app.owns(saved.collection)
            // What the collection has on the market, as of its last round of selling.
            val onSale = app.sales.shops.value[saved.collection.url]?.onSale.orEmpty()
            // What each NFT says about itself is read from the phone, off the main thread.
            val all = withContext(Dispatchers.IO) {
                Tickets(this@TicketsActivity, saved.collection).all
                    .map { Row(Described(it.h, it, app.pictures.about(it.h)), admitted[it.h], owned && !it.sent, it.id in onSale, it.sent) }
                    .sortedWith(compareBy(Described.ORDER) { it.nft })
            }
            val types = all.mapNotNull { it.nft.type }.distinct()
            if (type !in types) type = null
            tabs(types)
            val ofType = all.filter { type == null || it.nft.type == type }
            counts(ofType, owned)
            binding.status.text = listOfNotNull(
                resources.getQuantityString(R.plurals.collection_holds, all.size, all.size),
                getString(R.string.collection_sold, all.count { it.sold }).takeIf { owned },
                getString(priced()).lowercase().takeIf { owned && tickets },
                getString(R.string.collections_count, all.count { it.admission != null }, all.size).takeIf { !owned },
            ).joinToString(" · ")
            // A number is looked for as a number: "5" is the fifth, and not every one "of 50".
            val number = wanted.toIntOrNull()
            val shown = ofType.filter { row ->
                when (only) {
                    Only.IN -> row.admission != null
                    Only.OUT -> row.admission == null
                    Only.LISTED -> row.listed
                    Only.SOLD -> row.sold
                    null -> true
                } && if (number != null && row.nft.number != null) row.nft.number == number
                else row.nft.run { listOf(lead, rest, ticket?.title.orEmpty(), h) }.any { it.contains(wanted, ignoreCase = true) }
            }
            rows.submitList(shown)
            binding.empty.isVisible = shown.isEmpty()
            binding.empty.setText(
                when {
                    all.isEmpty() -> R.string.tickets_none
                    wanted.isNotEmpty() -> R.string.tickets_none_found
                    else -> only?.none ?: R.string.tickets_none_found
                }
            )
        }
    }

    /** A tab for each type, and one for all of them. A collection of one type needs none. */
    private fun tabs(types: List<String>) {
        binding.typesScroll.isVisible = types.size > 1
        binding.types.removeAllViews()
        if (types.size < 2) return
        for (one in listOf<String?>(null) + types) {
            pill(binding.types, one ?: getString(R.string.tickets_all), one == type) {
                type = one
                show()
            }
        }
    }

    /** How many are in, not in and, for a collection made here, listed and sold. Touching one shows only those. */
    private fun counts(rows: List<Row>, owned: Boolean) {
        binding.counts.removeAllViews()
        val numbers = listOf(
            Only.IN to rows.count { it.admission != null },
            Only.OUT to rows.count { it.admission == null },
            Only.LISTED to rows.count { it.listed },
            Only.SOLD to rows.count { it.sold },
        )
        for ((which, count) in numbers) {
            if (which in listOf(Only.LISTED, Only.SOLD) && !owned) continue
            pill(binding.counts, getString(which.label, count), which == only) {
                only = which.takeIf { it != only }
                show()
            }
        }
    }

    private fun pill(into: ViewGroup, label: String, chosen: Boolean, act: () -> Unit) {
        val pill = layoutInflater.inflate(R.layout.pill, into, false) as TextView
        pill.text = label
        pill.isSelected = chosen
        pill.setOnClickListener { act() }
        into.addView(pill)
    }

    /** Reads the collection's page again, for tickets minted since the list was saved. */
    private fun readAgain() {
        lifecycleScope.launch {
            val name = app.sync(saved.collection)
            binding.refresh.isRefreshing = false
            say(if (name == null) getString(R.string.tickets_not_read) else "")
            show()
        }
    }

    /** An NFT in the list: whether it has come in, and whether it is still its owner's to hand out. */
    private class Row(val nft: Described, val admission: Admission?, val yours: Boolean, val listed: Boolean, val sold: Boolean)

    private inner class Rows : ListAdapter<Row, Holder>(Same) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(RowTicketBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.show(getItem(position))
    }

    private inner class Holder(private val row: RowTicketBinding) :
        RecyclerView.ViewHolder(row.root) {
        private var loading: Job? = null

        init {
            row.picture.clipToOutline = true
        }

        fun show(shown: Row) {
            val nft = shown.nft
            val admission = shown.admission
            row.title.text = nft.lead
            row.detail.text = if (admission == null) {
                nft.rest.ifEmpty { "${nft.h.take(8)}…${nft.h.takeLast(4)}" }
            } else {
                // Today's admissions need only their time; a row has little room.
                val at = admission.at * 1000
                val day = if (DateUtils.isToday(at)) 0 else {
                    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
                }
                val time = DateUtils.formatDateTime(
                    this@TicketsActivity, at, DateUtils.FORMAT_SHOW_TIME or day
                )
                getString(R.string.tickets_when, time, admission.door)
            }
            // A row says what is unusual about it: that it is in, or that it is still the collection's, on the market or not.
            row.held.isVisible = shown.yours && admission == null
            row.held.setText(if (shown.listed) R.string.tickets_listed else R.string.tickets_yours)
            row.state.isVisible = admission != null
            row.state.setText(R.string.tickets_admitted)
            row.state.setBackgroundResource(R.drawable.bg_badge_good)
            row.state.setTextColor(getColor(R.color.black))
            row.root.setOnClickListener {
                val one = Intent(this@TicketsActivity, ResultActivity::class.java)
                    .putExtra(ResultActivity.EXTRA_TICKET, nft.h)
                    .putExtra(ResultActivity.EXTRA_COLLECTION, saved.collection.url)
                startActivity(one)
            }
            // A row is reused as the list scrolls; its last picture must not land on this one.
            loading?.cancel()
            row.picture.setImageDrawable(null)
            loading = lifecycleScope.launch {
                row.picture.setImageBitmap(app.pictures.of(saved.collection.site, nft.h))
            }
        }
    }

    private object Same : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old: Row, new: Row) = old.nft.h == new.nft.h
        override fun areContentsTheSame(old: Row, new: Row) =
            old.admission == new.admission && old.yours == new.yours && old.listed == new.listed && old.sold == new.sold &&
                old.nft.lead == new.nft.lead && old.nft.rest == new.nft.rest
    }
}
