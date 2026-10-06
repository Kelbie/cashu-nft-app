package cash.nonfungible.app

import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import cash.nonfungible.app.databinding.RowCollectionBinding
import cash.nonfungible.app.databinding.SheetHandOverBinding
import cash.nonfungible.app.databinding.SheetPickBinding
import cash.nonfungible.app.entry.Admitted
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Tickets
import cash.nonfungible.app.studio.Made
import cash.nonfungible.app.studio.Prices
import cash.nonfungible.app.studio.Sales
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Sells or sends one NFT to the guest at the door, without the organiser having to say which.
 * They are asked what type, if the collection has more than one, and the phone takes the
 * lowest-numbered of that type it still holds and has not let in. A type with none left is one
 * tap from having one more. `after` runs when it is over.
 */
fun AppCompatActivity.sellOrSend(collection: Collection, after: () -> Unit = {}) {
    val app = application as App
    val admitted = Admitted(this, collection).all
    val mine = Described.ORDER.let { order ->
        Tickets(this, collection).all.filter { !it.sent && it.id.isNotEmpty() && it.h !in admitted }
            .map { Described(it.h, it, app.pictures.about(it.h)) }.sortedWith(order)
    }
    // The types the collection was made with, and any its NFTs name besides.
    val types = (Made(this, collection).kinds.map { it.label } + mine.mapNotNull { it.type }).distinct()
    fun more() {
        val mint = Intent(this, MakeActivity::class.java)
        startActivity(mint.putExtra(MakeActivity.EXTRA_COLLECTION, collection.url))
    }
    fun hand(type: String?) {
        val next = mine.firstOrNull { type == null || it.type == type }?.ticket ?: return more()
        handOver(collection, next.id, next.h, next.title, after)
    }
    if (types.size < 2) return hand(types.firstOrNull())
    val sheet = SheetPickBinding.inflate(layoutInflater)
    val dialog = sheetOf(sheet.root)
    sheet.title.setText(R.string.hand_which)
    for (type in types) {
        val row = RowCollectionBinding.inflate(layoutInflater, sheet.rows, true)
        val left = mine.count { it.type == type }
        row.name.text = type
        row.chosen.isVisible = false
        row.count.text = if (left == 0) getString(R.string.hand_none_left) else resources.getQuantityString(R.plurals.hand_left, left, left)
        lifecycleScope.launch {
            val price = app.sales.asking(collection, type) ?: return@launch
            row.count.text = getString(R.string.hand_left_price, row.count.text, PricesActivity.sats(price))
        }
        row.root.setOnClickListener {
            dialog.dismiss()
            hand(type)
        }
    }
    dialog.show()
}

/**
 * One NFT the collection still holds, to the guest in front of the phone, a step at a time.
 * First whether it is sold or sent: for its price, the one set under Prices for its type, or
 * free. Then a code the guest scans: for a sale it is the site's own, so the NFT is theirs as
 * the money arrives, both or neither. When it is theirs the sheet says so, and they can be let
 * in from it. `after` runs when the sheet closes.
 */
fun AppCompatActivity.handOver(collection: Collection, id: String, h: String, title: String, after: () -> Unit = {}) {
    val app = application as App
    val sheet = SheetHandOverBinding.inflate(layoutInflater)
    sheet.title.text = title
    sheet.dots.behindWords = true
    val dialog = sheetOf(sheet.root)
    var going: Job? = null
    dialog.setOnDismissListener {
        going?.cancel()
        after()
    }
    /** The code is being made: dots, and a way to send what it will say. */
    fun begin(help: Int, wait: Int) {
        sheet.frame.isVisible = true
        sheet.help.setText(help)
        sheet.wait.setText(wait)
        sheet.first.isVisible = false
        sheet.second.setText(R.string.hand_share)
        sheet.second.isEnabled = false
    }
    fun failed(why: String?) {
        sheet.dots.isVisible = false
        sheet.wait.text = why?.takeIf { it.isNotBlank() } ?: getString(R.string.guest_slow)
        sheet.second.isEnabled = true
        sheet.second.setText(R.string.guest_close)
        sheet.second.setOnClickListener { dialog.dismiss() }
    }
    fun share(link: String) {
        sheet.dots.isVisible = false
        sheet.wait.isVisible = false
        sheet.code.setImageDrawable(QrCode(link))
        sheet.second.isEnabled = true
        sheet.second.setOnClickListener {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
            startActivity(Intent.createChooser(send, null))
        }
    }
    /** It is the guest's: the code gone, a tick, and the way to let them in with it. */
    fun done(said: String) {
        sheet.code.setImageDrawable(null)
        sheet.dots.isVisible = false
        sheet.wait.isVisible = false
        sheet.done.isVisible = true
        sheet.said.text = said
        sheet.frame.backgroundTintList = ColorStateList.valueOf(getColor(R.color.lime))
        sheet.tick.play()
        Feedback.give(sheet.root, Tone.GOOD, Config(this).sound)
        sheet.help.setText(R.string.hand_done_help)
        sheet.second.setText(R.string.guest_done)
        sheet.second.isEnabled = true
        sheet.second.setOnClickListener { dialog.dismiss() }
        val record = Admitted(this, collection)
        sheet.first.isVisible = true
        sheet.first.setText(if (h in record.all) R.string.tickets_admitted else R.string.hand_admit)
        sheet.first.isEnabled = h !in record.all
        sheet.first.setOnClickListener {
            record.record(h, getString(R.string.result_by_hand, Config(this).door))
            sheet.first.setText(R.string.tickets_admitted)
            sheet.first.isEnabled = false
        }
    }
    // Free: a link that makes it whoever's opens it first.
    sheet.second.setOnClickListener {
        begin(R.string.hand_help, R.string.hand_making)
        going = lifecycleScope.launch {
            try {
                val link = app.sales.gift(collection, id)
                share(link)
                // The organiser sees it go as the guest sees it come.
                if (app.sales.taken(link)) done(getString(R.string.hand_taken))
            } catch (e: IOException) {
                failed(getString(R.string.hand_failed, e.message.orEmpty()))
            } catch (e: TimeoutCancellationException) {
                failed(getString(R.string.hand_failed, getString(R.string.guest_slow)))
            }
        }
    }
    // For its price: the site's own sale, which sends the NFT as the money arrives. A type with
    // no price is only ever sent.
    lifecycleScope.launch {
        val price = Sales.kind(title)?.let { app.sales.asking(collection, it) } ?: return@launch
        if (sheet.frame.isVisible) return@launch
        sheet.first.isVisible = true
        sheet.first.text = getString(R.string.hand_sell, PricesActivity.sats(price))
        sheet.first.setOnClickListener {
            begin(R.string.hand_sell_help, R.string.hand_listing)
            going = lifecycleScope.launch {
                launch {
                    val sale = app.sales.sold.first { it.card == id }
                    going?.cancel()
                    done(getString(R.string.hand_paid, PricesActivity.sats(sale.price), sale.buyer.ifBlank { getString(R.string.hand_buyer) }))
                }
                // It is out for as long as this sheet is: closing it takes it back in.
                app.sales.sellAtTheDoor(collection, id) { listed, problem ->
                    if (listed == null) failed(getString(R.string.hand_unlisted, problem.orEmpty()))
                    // The code says where the collection is paid, for a guest's app to pay there.
                    else share("${collection.site}/market/${listed.listing}?offer=1&mint=" + Uri.encode(Prices(this@handOver, collection).plan.mint))
                }
            }
        }
    }
    dialog.show()
}
