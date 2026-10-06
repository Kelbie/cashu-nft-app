package cash.nonfungible.app

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import cash.nonfungible.app.databinding.ActivityEventBinding
import cash.nonfungible.app.databinding.RowTicketBinding
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.guest.ForSale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * A collection, for somebody looking through it: every NFT it lists, in the order of their
 * numbers, whether or not it is for sale. One that is says what it costs. Touching an NFT opens
 * it as its own page, where one that is for sale can be bought.
 */
class EventActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEventBinding
    private lateinit var event: Collection
    private val app get() = application as App
    private val rows = Rows()

    /** An NFT of the collection, and the cheapest it is for sale at, if it is. */
    private class Row(val nft: Described, val sale: ForSale?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        event = intent.getStringExtra(EXTRA_COLLECTION)?.let(Collection::parse) ?: return finish()
        binding = ActivityEventBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.title.text = intent.getStringExtra(EXTRA_NAME)
        binding.list.adapter = rows
    }

    override fun onStart() {
        super.onStart()
        // Coming back from an NFT that was bought, it is no longer for sale.
        if (::event.isInitialized) look()
    }

    private fun look() {
        if (rows.itemCount == 0) binding.note.text = getString(R.string.event_looking, event.site.substringAfter("://"))
        lifecycleScope.launch {
            val found = try {
                app.guest.browse(event)
            } catch (e: IOException) {
                return@launch binding.note.setText(R.string.event_unread)
            }
            if (binding.title.text.isNullOrEmpty()) binding.title.text = found.name
            val selling = found.selling.values
            binding.note.text = listOfNotNull(
                resources.getQuantityString(R.plurals.collection_holds, found.listed.size, found.listed.size),
                if (selling.isEmpty()) getString(R.string.event_none)
                else resources.getQuantityString(R.plurals.event_selling, selling.size, selling.size, PricesActivity.sats(selling.minOf { it.price })),
            ).joinToString(" · ")
            // What each NFT says about itself is read from the phone, off the main thread.
            val all = withContext(Dispatchers.IO) {
                found.listed.map { Row(Described(it.h, it, app.pictures.about(it.h)), found.selling[it.h]) }
                    .sortedWith(compareBy(Described.ORDER) { it.nft })
            }
            rows.submitList(all)
        }
    }

    private inner class Rows : ListAdapter<Row, Holder>(Same) {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(RowTicketBinding.inflate(LayoutInflater.from(parent.context), parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) = holder.show(getItem(position))
    }

    private inner class Holder(private val row: RowTicketBinding) : RecyclerView.ViewHolder(row.root) {
        private var loading: Job? = null

        init {
            row.picture.clipToOutline = true
            row.held.isVisible = false
            row.state.setBackgroundResource(R.drawable.bg_badge_good)
            row.state.setTextColor(getColor(R.color.black))
        }

        fun show(shown: Row) {
            val nft = shown.nft
            val sale = shown.sale
            row.title.text = nft.lead
            row.detail.text = when {
                sale == null -> nft.rest.ifEmpty { "${nft.h.take(8)}…${nft.h.takeLast(4)}" }
                sale.seller == event.pubkey -> getString(R.string.event_from_host)
                else -> getString(R.string.event_resold, sale.sellerName)
            }
            // The one thing a row says besides what the NFT is: what it costs, when it can be had.
            row.state.isVisible = sale != null
            row.state.text = sale?.let { getString(R.string.guest_sats, PricesActivity.sats(it.price)) }
            row.root.setOnClickListener {
                val open = Intent(this@EventActivity, NftActivity::class.java)
                    .putExtra(NftActivity.EXTRA_H, nft.h)
                    .putExtra(NftActivity.EXTRA_COLLECTION, event.url)
                startActivity(if (sale == null) open else open.putExtra(NftActivity.EXTRA_LISTING, sale.listing))
            }
            // A row is reused as the list scrolls; its last picture must not land on this one.
            loading?.cancel()
            row.picture.setImageDrawable(null)
            loading = lifecycleScope.launch {
                row.picture.setImageBitmap(app.pictures.of(event.site, nft.h))
            }
        }
    }

    private object Same : DiffUtil.ItemCallback<Row>() {
        override fun areItemsTheSame(old: Row, new: Row) = old.nft.h == new.nft.h
        override fun areContentsTheSame(old: Row, new: Row) =
            old.sale?.price == new.sale?.price && old.sale?.listing == new.sale?.listing &&
                old.nft.lead == new.nft.lead && old.nft.rest == new.nft.rest
    }

    companion object {
        const val EXTRA_COLLECTION = "collection"
        const val EXTRA_NAME = "name"
    }
}
