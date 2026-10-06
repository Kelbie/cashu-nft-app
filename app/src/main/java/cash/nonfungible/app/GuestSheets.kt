package cash.nonfungible.app

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import cash.nonfungible.app.databinding.SheetGuestBinding
import cash.nonfungible.app.entry.Ticket
import cash.nonfungible.app.guest.ForSale
import cash.nonfungible.app.guest.Guest
import cash.nonfungible.app.guest.Mine
import cash.nonfungible.app.guest.Scanned
import cash.nonfungible.app.studio.Prices
import com.google.android.material.bottomsheet.BottomSheetDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

/*
 * What a guest is told and asked, one sheet at a time. Each is a thing a guest does: answer a
 * door, take a ticket they are given, buy one, give one away, sell one on, look at their money.
 */

/** What a sheet has the guest's leave to answer a door with, for as long as the sheet is up. */
typealias Armed = suspend (request: String) -> String?

private val AppCompatActivity.app get() = application as App
private val AppCompatActivity.guest get() = app.guest

/** A sheet, shown, and what to do when it closes. */
private fun AppCompatActivity.sheet(after: () -> Unit = {}): Pair<SheetGuestBinding, BottomSheetDialog> {
    val sheet = SheetGuestBinding.inflate(layoutInflater)
    val dialog = sheetOf(sheet.root)
    sheet.first.setOnClickListener { dialog.dismiss() }
    dialog.setOnDismissListener { after() }
    dialog.show()
    return sheet to dialog
}

/** Does something that asks the site, and says why not when it cannot be done. */
private suspend fun <T> SheetGuestBinding.attempt(doing: suspend () -> T): T? = try {
    doing()
} catch (e: IOException) {
    say(e.message.orEmpty())
    null
} catch (e: TimeoutCancellationException) {
    say(root.context.getString(R.string.guest_slow))
    null
}

private fun SheetGuestBinding.say(what: String) {
    note.text = what
    note.isVisible = what.isNotEmpty()
}

/** Puts an NFT's picture on a sheet, as soon as it is to be had. */
private fun AppCompatActivity.picture(sheet: SheetGuestBinding, h: String) {
    sheet.stage.isVisible = true
    sheet.mosaic.isVisible = true
    sheet.mosaic.begin(1)
    lifecycleScope.launch { sheet.mosaic.made(app.pictures.of(Config(this@picture).site, h)) }
}

/**
 * Does what a code, a link or a door the phone was held to asks for. `from` is the NFT the guest
 * came from, if they came from one; `arm` gives a sheet the guest's leave to answer a door the
 * phone is next held to; `after` runs when what the guest holds may have changed.
 */
fun AppCompatActivity.took(text: String, from: String?, arm: (Armed?) -> Unit, after: () -> Unit, outside: Boolean = false) {
    // Money is read first: ecash to take in, or an invoice to pay.
    if (ECASH.containsMatchIn(text)) return takeEcash(text, after)
    INVOICE.find(text)?.let { return payInvoice(it.value, after, outside) }
    when (val read = Scanned.read(text, Config(this))) {
        is Scanned.Door -> atDoor(read.request, from, arm)
        // Taking and buying leave the phone knowing what it holds: nothing more is asked.
        is Scanned.Gift -> takeGift(read.link, after)
        is Scanned.Sale -> buy(read, after)
        is Scanned.Event -> startActivity(
            Intent(this, EventActivity::class.java).putExtra(EventActivity.EXTRA_COLLECTION, read.collection.url)
        )
        null -> sheet().first.title.setText(R.string.guest_not_ours)
    }
}

/**
 * A door has asked to see a ticket. The guest is told which door and for what, and agrees
 * before anything is sent: reading a code is not yet agreeing to answer it. `prefer` is the
 * NFT they came from, if they came from one. The answer goes over the network when the phone
 * has one. When it has none the answer is handed to the door instead: the guest holds the phone
 * to it, or the door scans the code the sheet then shows.
 */
fun AppCompatActivity.atDoor(request: String, prefer: String? = null, arm: (Armed?) -> Unit = {}) {
    val (sheet, dialog) = sheet { arm(null) }
    sheet.title.setText(R.string.guest_door_reading)
    lifecycleScope.launch {
        var asked = sheet.attempt { guest.asked(request, prefer) }
        // Something bought and not yet brought in may be the ticket: it is looked for once.
        if (asked != null && asked.mine.isEmpty() && guest.me != null) {
            guest.mine()
            asked = sheet.attempt { guest.asked(request, prefer) }
        }
        if (asked == null) return@launch sheet.title.setText(R.string.guest_door_failed)
        if (asked.mine.isEmpty()) {
            sheet.title.setText(R.string.guest_door_none)
            sheet.text.text = if (asked.event.isEmpty()) "" else getString(R.string.guest_door_none_why, asked.door, asked.event)
            return@launch
        }
        val one = asked.mine.first()
        sheet.title.text = asked.door
        sheet.text.text = if (asked.event.isEmpty()) getString(R.string.guest_door_asks_plain)
        else getString(R.string.guest_door_asks, asked.event)
        picture(sheet, one.h)
        fun wasShown() {
            sheet.title.setText(R.string.guest_door_sent)
            sheet.text.text = getString(R.string.guest_door_look, asked.door)
            sheet.say("")
            sheet.code.isVisible = false
            sheet.mosaic.isVisible = false
            sheet.stage.isVisible = true
            sheet.tick.isVisible = true
            sheet.tick.play()
            Feedback.give(sheet.root, Tone.GOOD, sound = false)
            sheet.first.isEnabled = true
            sheet.first.setText(R.string.guest_done)
            sheet.first.setOnClickListener { dialog.dismiss() }
        }
        fun show(shown: List<Mine>) {
            sheet.first.isEnabled = false
            sheet.second.isVisible = false
            sheet.say(getString(R.string.guest_door_showing))
            lifecycleScope.launch {
                val sent = try {
                    withTimeout(SEND_MS) { guest.show(request, shown) }
                    true
                } catch (e: IOException) {
                    false
                } catch (e: TimeoutCancellationException) {
                    false
                }
                if (sent) return@launch wasShown()
                // No network, or no relay took it. The door has one: the answer is handed to it.
                val code = sheet.attempt { guest.prove(request, shown) }
                sheet.first.isEnabled = true
                if (code == null) return@launch
                sheet.title.setText(R.string.guest_door_direct)
                sheet.text.setText(R.string.guest_door_direct_help)
                sheet.say("")
                sheet.mosaic.isVisible = false
                sheet.code.isVisible = true
                sheet.code.setImageDrawable(QrCode(code))
                sheet.first.setText(R.string.guest_done)
                sheet.first.setOnClickListener { dialog.dismiss() }
                // The door shows a new code by then: the phone answers whichever it is held to.
                if (!dialog.isShowing) return@launch
                arm { again ->
                    guest.prove(again, shown).also { withContext(Dispatchers.Main) { wasShown() } }
                }
            }
        }
        // Somebody holding a group's tickets shows them together; or just their own.
        if (asked.mine.size > 1) {
            sheet.first.text = getString(R.string.guest_door_show_all, asked.mine.size)
            sheet.second.isVisible = true
            sheet.second.setText(R.string.guest_door_show_one)
            sheet.second.setOnClickListener { show(listOf(one)) }
        } else {
            sheet.first.setText(R.string.guest_door_show)
        }
        sheet.first.setOnClickListener { show(asked.mine) }
    }
}

/** A ticket given by a link is taken at once: scanning it was the asking. Dots, then the picture. */
fun AppCompatActivity.takeGift(link: String, after: () -> Unit) {
    val (sheet, dialog) = sheet(after)
    sheet.title.setText(R.string.guest_gift)
    sheet.text.setText(R.string.guest_gift_taking)
    sheet.stage.isVisible = true
    sheet.mosaic.isVisible = true
    sheet.mosaic.begin(1)
    sheet.first.isEnabled = false
    lifecycleScope.launch {
        val mine = sheet.attempt { guest.claim(link) }
        sheet.first.isEnabled = true
        if (mine == null) {
            sheet.title.setText(R.string.guest_gift_failed)
            sheet.text.text = ""
            sheet.stage.isVisible = false
            return@launch
        }
        sheet.mosaic.made(app.pictures.of(Config(this@takeGift).site, mine.h))
        celebrate(sheet)
        sheet.title.setText(R.string.guest_yours)
        sheet.text.text = mine.title
        sheet.first.setText(R.string.guest_done)
        Feedback.give(sheet.root, Tone.GOOD, Config(this@takeGift).sound)
        if (!dialog.isShowing) after()
    }
}

/** A ticket for sale: what it is and what it costs, and one button that pays for it. */
fun AppCompatActivity.buy(asked: Scanned.Sale, after: () -> Unit) {
    val (sheet, dialog) = sheet(after)
    sheet.title.setText(R.string.guest_sale_reading)
    lifecycleScope.launch {
        val sale = try {
            guest.listing(asked.listing, asked.mint)
        } catch (e: IOException) {
            sheet.say(e.message.orEmpty())
            return@launch sheet.title.setText(R.string.guest_sale_unread)
        }
        if (sale == null) return@launch sheet.title.setText(R.string.guest_sale_gone)
        offer(sheet, dialog, sale, after)
    }
}

/** As [buy], for a sale that has been read already. */
fun AppCompatActivity.buy(sale: ForSale, after: () -> Unit) {
    val (sheet, dialog) = sheet(after)
    offer(sheet, dialog, sale, after)
}

private fun AppCompatActivity.offer(sheet: SheetGuestBinding, dialog: BottomSheetDialog, sale: ForSale, after: () -> Unit) {
    val price = PricesActivity.sats(sale.price)
    sheet.title.text = sale.title
    sheet.text.text = getString(R.string.guest_sale_from, price, sale.sellerName)
    picture(sheet, sale.h)
    sheet.first.text = getString(R.string.guest_sale_pay, price)
    // Whether the price has been locked for the seller: from then on nothing is paid again.
    var locked = false
    sheet.first.setOnClickListener {
        sheet.first.isEnabled = false
        lifecycleScope.launch {
            val arrived = sheet.attempt {
                guest.buy(sale) { step ->
                    when (step) {
                        is Guest.Step.Pay -> if (step.test) {
                            sheet.say(getString(R.string.guest_pay_test, PricesActivity.sats(step.sats)))
                        } else {
                            // Real money comes from the guest's own Lightning wallet: one on
                            // this phone, or another that reads the code.
                            sheet.mosaic.isVisible = false
                            sheet.code.isVisible = true
                            sheet.code.setImageDrawable(QrCode(step.invoice.uppercase()))
                            sheet.code.setOnClickListener { payWith(step.invoice, sheet) }
                            sheet.say(getString(R.string.guest_pay_invoice, PricesActivity.sats(step.sats), step.mint.substringAfter("://")))
                            sheet.second.isVisible = true
                            sheet.second.setText(R.string.guest_pay_open)
                            sheet.second.setOnClickListener { payWith(step.invoice, sheet) }
                        }
                        Guest.Step.Locking -> {
                            locked = true
                            sheet.code.isVisible = false
                            sheet.mosaic.isVisible = true
                            sheet.second.isVisible = false
                            sheet.say(getString(R.string.guest_paying))
                        }
                        Guest.Step.Waiting -> sheet.say(getString(R.string.guest_waiting))
                    }
                }
            }
            sheet.first.isEnabled = true
            // A guest who closed the sheet while it worked still sees what came of it.
            if (!dialog.isShowing) after()
            // What failed before any money was locked can be tried again with the same button.
            if (arrived == null && !locked) {
                sheet.code.isVisible = false
                sheet.second.isVisible = false
                return@launch
            }
            sheet.first.setText(R.string.guest_done)
            sheet.first.setOnClickListener { dialog.dismiss() }
            if (arrived == true) {
                celebrate(sheet)
                sheet.title.setText(R.string.guest_yours)
                sheet.text.text = sale.title
                sheet.say("")
                Feedback.give(sheet.root, Tone.GOOD, Config(this@offer).sound)
            } else {
                // The money is locked for the seller, who has not answered. Either they do, or it comes back.
                sheet.say(getString(R.string.guest_not_yet))
            }
        }
    }
}

/** The tick over a picture that says it is the guest's now. It goes, and leaves the picture. */
private fun celebrate(sheet: SheetGuestBinding) = sheet.tick.play(passing = true)

/**
 * Sends an NFT to somebody: a code they scan, or a link sent to them. The guest is told what
 * that means before any link is made, because whoever opens one first has the NFT.
 */
fun AppCompatActivity.send(ticket: Ticket, after: () -> Unit) {
    val (sheet, dialog) = sheet(after)
    sheet.title.setText(R.string.nft_send)
    sheet.text.setText(R.string.guest_send_help)
    picture(sheet, ticket.h)
    sheet.second.isVisible = true
    sheet.second.setText(R.string.guest_close)
    sheet.second.setOnClickListener { dialog.dismiss() }
    sheet.first.setText(R.string.guest_send_make)
    sheet.first.setOnClickListener {
        sheet.say(getString(R.string.hand_making))
        sheet.first.isEnabled = false
        lifecycleScope.launch {
            val link = sheet.attempt { guest.give(ticket.id) }
            sheet.first.isEnabled = true
            if (link == null) return@launch
            sheet.say("")
            sheet.mosaic.isVisible = false
            sheet.code.isVisible = true
            sheet.code.setImageDrawable(QrCode(link))
            sheet.second.setText(R.string.guest_done)
            sheet.first.setText(R.string.hand_share)
            sheet.first.setOnClickListener {
                val shared = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, link)
                startActivity(Intent.createChooser(shared, null))
            }
            // The guest sees it go as its taker sees it come.
            if (app.sales.taken(link) && dialog.isShowing) {
                sheet.code.isVisible = false
                sheet.mosaic.isVisible = true
                celebrate(sheet)
                sheet.title.setText(R.string.hand_taken)
                sheet.text.text = ""
                sheet.first.isVisible = false
                Feedback.give(sheet.root, Tone.GOOD, Config(this@send).sound)
            }
        }
    }
}

/** Sells a ticket the guest cannot use: a price, and it is on the site's market. */
fun AppCompatActivity.sell(ticket: Ticket, after: () -> Unit) {
    val (sheet, dialog) = sheet(after)
    val asking = guest.asks[ticket.id]
    sheet.title.setText(R.string.nft_sell)
    sheet.text.setText(if (guest.mint == Prices.TEST_MINT) R.string.guest_sell_help_test else R.string.guest_sell_help)
    sheet.amount.isVisible = true
    sheet.amount.setHint(R.string.guest_sell_price)
    sheet.amount.setText(asking?.toString().orEmpty())
    sheet.first.setText(if (asking == null) R.string.guest_sell_start else R.string.guest_sell_change)
    fun price() = sheet.amount.text.toString().toLongOrNull()?.takeIf { it > 0 }
    sheet.first.isEnabled = price() != null
    sheet.amount.doAfterTextChanged { sheet.first.isEnabled = price() != null }
    sheet.first.setOnClickListener {
        val price = price() ?: return@setOnClickListener
        sheet.first.isEnabled = false
        sheet.say(getString(R.string.hand_listing))
        lifecycleScope.launch {
            val listed = sheet.attempt { guest.ask(ticket.id, price) }
            sheet.first.isEnabled = true
            if (listed != null) dialog.dismiss()
        }
    }
    sheet.second.isVisible = asking != null
    sheet.second.setText(R.string.guest_sell_stop)
    sheet.second.setOnClickListener {
        sheet.second.isEnabled = false
        lifecycleScope.launch {
            val done = sheet.attempt { guest.unask(ticket.id) }
            sheet.second.isEnabled = true
            if (done != null) dialog.dismiss()
        }
    }
}

/** What a mint is called, for a line of text: its name if the wallet has told it, else its address. */
private fun AppCompatActivity.called(mint: String): String =
    guest.purse?.at(mint)?.name?.substringBefore(" (")?.ifBlank { null } ?: mint.substringAfter("://")

/**
 * Puts sats in the wallet, at the mint it is showing: an invoice to pay from any Lightning
 * wallet, or ecash somebody copied. The sheet watches the invoice and says when the money is in.
 */
fun AppCompatActivity.receiveSats(after: () -> Unit) {
    val (sheet, dialog) = sheet(after)
    val at = guest.viewing
    val test = at == Prices.TEST_MINT
    sheet.title.setText(R.string.wallet_receive)
    sheet.text.text = getString(if (test) R.string.wallet_receive_test else R.string.wallet_receive_help, called(at))
    sheet.amount.isVisible = true
    sheet.amount.setHint(R.string.guest_wallet_add_hint)
    sheet.second.isVisible = true
    sheet.second.setText(R.string.wallet_paste)
    sheet.second.setOnClickListener {
        val copied = getSystemService<ClipboardManager>()?.primaryClip?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty()
        if (!ECASH.containsMatchIn(copied)) return@setOnClickListener sheet.say(getString(R.string.wallet_paste_none))
        dialog.dismiss()
        takeEcash(copied, after)
    }
    fun sats() = sheet.amount.text.toString().toLongOrNull()?.takeIf { it > 0 }
    sheet.first.setText(R.string.wallet_invoice)
    sheet.first.isEnabled = false
    sheet.amount.doAfterTextChanged { sheet.first.isEnabled = sats() != null }
    sheet.first.setOnClickListener {
        val sats = sats() ?: return@setOnClickListener
        sheet.first.isEnabled = false
        sheet.first.setText(R.string.wallet_invoice_making)
        sheet.say("")
        lifecycleScope.launch {
            val asked = sheet.attempt { guest.invoice(sats, at) }
            sheet.first.isEnabled = true
            if (asked == null) return@launch sheet.first.setText(R.string.wallet_invoice)
            val (invoice, id) = asked
            sheet.amount.isVisible = false
            sheet.title.text = getString(R.string.guest_sats, PricesActivity.sats(sats))
            if (test) {
                sheet.text.text = getString(R.string.guest_pay_test, PricesActivity.sats(sats))
                sheet.second.isVisible = false
                sheet.first.setText(R.string.guest_close)
                sheet.first.setOnClickListener { dialog.dismiss() }
            } else {
                sheet.text.text = getString(R.string.wallet_invoice_help, called(at))
                sheet.stage.isVisible = true
                sheet.code.isVisible = true
                sheet.code.setImageDrawable(QrCode(invoice.uppercase()))
                sheet.say(getString(R.string.wallet_waiting))
                sheet.second.setText(R.string.wallet_copy)
                sheet.second.setOnClickListener {
                    copy(invoice, secret = false)
                    sheet.say(getString(R.string.wallet_copied))
                }
                sheet.first.setText(R.string.guest_pay_open)
                sheet.first.setOnClickListener { payWith(invoice, sheet) }
            }
            // Asked about until it is paid, or the sheet is closed.
            while (dialog.isShowing && sheet.attempt { guest.paid(id) } != true) delay(1500)
            if (!dialog.isShowing) return@launch
            sheet.code.isVisible = false
            sheet.stage.isVisible = true
            sheet.tick.isVisible = true
            sheet.tick.play()
            sheet.title.setText(R.string.wallet_received)
            sheet.text.text = getString(R.string.wallet_received_at, PricesActivity.sats(sats), called(at))
            sheet.say("")
            sheet.second.isVisible = false
            sheet.first.setText(R.string.guest_done)
            sheet.first.setOnClickListener { dialog.dismiss() }
            Feedback.give(sheet.root, Tone.GOOD, Config(this@receiveSats).sound)
        }
    }
}

/** Takes in ecash somebody copied or showed as a code: scanning it, or pasting it, was the asking. */
fun AppCompatActivity.takeEcash(token: String, after: () -> Unit) {
    val (sheet, dialog) = sheet(after)
    sheet.title.setText(R.string.wallet_ecash)
    sheet.text.setText(R.string.wallet_ecash_taking)
    sheet.first.isEnabled = false
    lifecycleScope.launch {
        val taken = sheet.attempt { guest.receive(ECASH.find(token)?.value ?: token) }
        sheet.first.isEnabled = true
        sheet.first.setText(R.string.guest_done)
        if (taken == null) {
            sheet.title.setText(R.string.wallet_ecash_failed)
            sheet.text.text = ""
            return@launch
        }
        sheet.stage.isVisible = true
        sheet.tick.isVisible = true
        sheet.tick.play()
        sheet.title.setText(R.string.wallet_received)
        sheet.text.text = getString(R.string.wallet_received_at, PricesActivity.sats(taken.second), called(taken.first))
        Feedback.give(sheet.root, Tone.GOOD, Config(this@takeEcash).sound)
        if (!dialog.isShowing) after()
    }
}

/**
 * Sends sats out of the wallet, from the mint it is showing: to a Lightning invoice, or as
 * ecash to hand to somebody. Ecash taken out is all there is of that money, so it is kept here
 * until its owner says they have it.
 */
fun AppCompatActivity.sendSats(after: () -> Unit) {
    // Ecash taken out and not yet said to be safe comes first: it is money with nowhere else to be.
    guest.takenOut?.let { return showEcash(it, after) }
    val (sheet, dialog) = sheet(after)
    val at = guest.viewing
    val have = guest.purse?.at(at)?.available ?: 0
    sheet.title.setText(R.string.wallet_send)
    sheet.text.text = getString(R.string.wallet_send_help, PricesActivity.sats(have), called(at))
    sheet.second.isVisible = true
    sheet.second.setText(R.string.wallet_as_ecash)
    sheet.first.setText(R.string.wallet_pay_invoice)
    sheet.second.isEnabled = have > 0
    sheet.first.isEnabled = have > 0
    if (have == 0L) sheet.say(getString(R.string.wallet_send_none))
    sheet.first.setOnClickListener {
        dialog.dismiss()
        payInvoice(null, after)
    }
    sheet.second.setOnClickListener {
        sheet.text.setText(R.string.wallet_ecash_help)
        sheet.amount.isVisible = true
        sheet.amount.setHint(getString(R.string.wallet_ecash_hint, PricesActivity.sats(have)))
        sheet.second.setText(R.string.guest_close)
        sheet.second.setOnClickListener { dialog.dismiss() }
        sheet.first.setText(R.string.wallet_ecash_make)
        sheet.first.setOnClickListener {
            val typed = sheet.amount.text.toString()
            val sats = if (typed.isBlank()) have else typed.toLongOrNull()?.takeIf { it in 1..have }
                ?: return@setOnClickListener sheet.say(getString(R.string.wallet_too_much, PricesActivity.sats(have)))
            sheet.first.isEnabled = false
            sheet.first.setText(R.string.wallet_ecash_making)
            lifecycleScope.launch {
                val token = sheet.attempt { guest.takeOut(sats, at) }
                sheet.first.isEnabled = true
                sheet.first.setText(R.string.wallet_ecash_make)
                if (token == null) return@launch
                // The token is kept whatever became of the screen: it is shown when Send is next opened.
                if (isFinishing || isDestroyed) return@launch
                dialog.dismiss()
                showEcash(token, after)
            }
        }
    }
}

/** Ecash that has left the wallet: its code, the way to copy or share it, and the button that says it is safe. */
private fun AppCompatActivity.showEcash(token: String, after: () -> Unit) {
    val (sheet, dialog) = sheet(after)
    sheet.title.setText(R.string.wallet_ecash_out)
    sheet.text.setText(R.string.wallet_ecash_out_help)
    sheet.stage.isVisible = true
    sheet.code.isVisible = true
    sheet.code.setImageDrawable(QrCode(token))
    sheet.second.isVisible = true
    sheet.second.setText(R.string.wallet_copy)
    sheet.second.setOnClickListener {
        copy(token, secret = true)
        sheet.say(getString(R.string.wallet_copied))
    }
    sheet.first.setText(R.string.wallet_ecash_have)
    sheet.first.setOnClickListener {
        confirm(getString(R.string.wallet_ecash_sure), getString(R.string.wallet_ecash_sure_help), getString(R.string.wallet_ecash_delete), forGood = true) {
            guest.takenOut = null
            dialog.dismiss()
        }
    }
}

/**
 * Pays a Lightning invoice out of the wallet. What it will cost is asked first and shown, and
 * nothing is paid until the button that names the amount is pressed. `given` is an invoice that
 * was scanned; with none, one is pasted. One that came from `outside`, in a link another app
 * opened, is not even asked about until whoever holds the phone says to: asking sets sats aside.
 *
 * Asking what an invoice costs sets the sats to pay it aside. If the sheet is closed without
 * paying, they are let go of again.
 */
fun AppCompatActivity.payInvoice(given: String?, after: () -> Unit, outside: Boolean = false) {
    // What was asked about and not yet paid, to let go of if the sheet closes on it.
    var asked: cash.nonfungible.app.guest.Quote? = null
    val (sheet, dialog) = sheet {
        asked?.let { quote -> lifecycleScope.launch { guest.unquote(quote) } }
        after()
    }
    val at = guest.viewing
    sheet.title.setText(R.string.wallet_pay_invoice)
    sheet.text.text = getString(R.string.wallet_pay_help, called(at))
    fun check(invoice: String) {
        sheet.words.isVisible = false
        sheet.second.isVisible = false
        sheet.first.isEnabled = false
        sheet.first.setText(R.string.wallet_pay_checking)
        sheet.say("")
        lifecycleScope.launch {
            val quote = sheet.attempt { guest.quote(invoice, at) }
            sheet.first.isEnabled = true
            if (quote == null) {
                sheet.first.setText(R.string.guest_close)
                return@launch sheet.first.setOnClickListener { dialog.dismiss() }
            }
            asked = quote
            // Closed while it was being asked about: what was set aside is let go of at once.
            if (!dialog.isShowing) return@launch guest.unquote(quote)
            sheet.title.text = getString(R.string.guest_sats, PricesActivity.sats(quote.amount))
            sheet.text.text = getString(R.string.wallet_pay_fee, PricesActivity.sats(quote.fee), called(at))
            sheet.second.isVisible = true
            sheet.second.setText(android.R.string.cancel)
            sheet.second.setOnClickListener { dialog.dismiss() }
            sheet.first.text = getString(R.string.wallet_pay, PricesActivity.sats(quote.amount))
            sheet.first.setOnClickListener {
                sheet.first.isEnabled = false
                sheet.second.isVisible = false
                sheet.first.setText(R.string.guest_paying)
                // Money is moving: the sheet stays until it has or has not.
                dialog.setCancelable(false)
                lifecycleScope.launch {
                    // From here it is the wallet's to see through: closing the sheet lets go of nothing.
                    asked = null
                    val paid = sheet.attempt { guest.pay(quote) }
                    dialog.setCancelable(true)
                    sheet.first.isEnabled = true
                    sheet.first.setText(R.string.guest_done)
                    sheet.first.setOnClickListener { dialog.dismiss() }
                    if (paid == null) return@launch sheet.title.setText(R.string.wallet_pay_failed)
                    if (!paid) {
                        // The mint has it and has not finished. That is not paid, and is not said to be.
                        sheet.title.setText(R.string.wallet_pay_pending)
                        sheet.text.setText(R.string.wallet_pay_pending_help)
                        return@launch
                    }
                    sheet.stage.isVisible = true
                    sheet.tick.isVisible = true
                    sheet.tick.play()
                    sheet.title.setText(R.string.wallet_paid)
                    sheet.text.text = getString(R.string.wallet_paid_help, PricesActivity.sats(quote.amount))
                    Feedback.give(sheet.root, Tone.GOOD, Config(this@payInvoice).sound)
                }
            }
        }
    }
    if (given != null && !outside) return check(given)
    sheet.words.isVisible = true
    if (given != null) {
        sheet.words.setText(given)
        sheet.text.setText(R.string.wallet_pay_outside)
    }
    sheet.second.isVisible = true
    sheet.second.setText(R.string.collection_paste)
    sheet.second.setOnClickListener {
        getSystemService<ClipboardManager>()?.primaryClip?.getItemAt(0)?.coerceToText(this)?.let(sheet.words::setText)
    }
    fun typed() = INVOICE.find(sheet.words.text.toString())?.value
    sheet.first.setText(R.string.wallet_pay_check)
    sheet.first.isEnabled = typed() != null
    sheet.words.doAfterTextChanged { sheet.first.isEnabled = typed() != null }
    sheet.first.setOnClickListener { typed()?.let(::check) }
}

/** Ecash as it is written, and a Lightning invoice. Either may come with its scheme in front. */
private val ECASH = Regex("cashu[AB][A-Za-z0-9_=-]{20,}")
private val INVOICE = Regex("ln(bc|tb|bcrt)[0-9a-z]{20,}", RegexOption.IGNORE_CASE)

/** Hands an invoice to a Lightning wallet on this phone, or leaves it on the clipboard for one elsewhere. */
private fun AppCompatActivity.payWith(invoice: String, sheet: SheetGuestBinding) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("lightning:$invoice")))
    } catch (e: ActivityNotFoundException) {
        copy(invoice, secret = false)
        sheet.say(getString(R.string.guest_pay_copied))
    }
}

private fun AppCompatActivity.copy(text: String, secret: Boolean) {
    val copied = ClipData.newPlainText(getString(R.string.app_name), text)
    if (secret) copied.description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    getSystemService<ClipboardManager>()?.setPrimaryClip(copied)
}

/** How long an answer may take to reach a door over the network before it is handed over instead. */
private const val SEND_MS = 7000L
