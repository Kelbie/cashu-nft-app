package cash.nonfungible.app

import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.os.Bundle
import android.os.SystemClock
import android.text.format.DateUtils
import android.util.Log
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cash.nonfungible.app.databinding.ActivityHomeBinding
import cash.nonfungible.app.databinding.RowAccountBinding
import cash.nonfungible.app.databinding.RowCollectionBinding
import cash.nonfungible.app.databinding.RowMintBinding
import cash.nonfungible.app.databinding.RowMovedBinding
import cash.nonfungible.app.databinding.RowTicketBinding
import cash.nonfungible.app.databinding.SheetAccountsBinding
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Entry
import cash.nonfungible.app.entry.Tickets
import cash.nonfungible.app.guest.Moved
import cash.nonfungible.app.studio.Prices
import cash.nonfungible.app.tap.DoorTag
import cash.nonfungible.app.tap.Heard
import cash.nonfungible.app.tap.Taps
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Where the app opens: whose account it is, and the three things an account has. Its NFTs,
 * tickets first; the collections it made and the doors it keeps; its wallet. What can be done
 * with the one in view is at the foot, and one button there reads whatever code it is shown.
 */
class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var config: Config
    private val app get() = application as App
    private val guest get() = app.guest

    /** What a sheet has the account's leave to answer a door with, while that sheet is up. */
    private var armed: Armed? = null

    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { read ->
        read.data?.getStringExtra(ScanActivity.EXTRA_TEXT)?.let(::take)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = Config(this)
        // Nobody has said what to call them yet. That is asked first, and whatever opened the
        // app is kept for when they have.
        if (app.accounts.all.isEmpty()) {
            given(intent)?.let { config.pending = it }
            startActivity(Intent(this, WelcomeActivity::class.java))
            return finish()
        }
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.account.setOnClickListener { accounts() }
        binding.settings.setOnClickListener { startActivity(Intent(this, SettingsActivity::class.java)) }
        binding.refresh.setOnRefreshListener(::refresh)
        // A phone held to a door with this screen up answers it, when it is plain what with.
        Taps(this, ::heard) { request -> armed?.invoke(request) ?: answer(request) }
        // A link that was opened with the app is read as if it had been scanned, and so is a
        // door the phone was held to while the app was closed.
        val came = if (savedInstanceState == null) given(intent) ?: config.pending else null
        config.pending = null
        if (came != null) {
            // What comes in is an NFT or money: either way it is looked at where it lands.
            config.tab = Tab.NFTS.name
            take(came, outside = true)
        } else if (savedInstanceState == null) {
            // A phone that was a door when it was put away is a door again.
            config.doorOpen?.let(Collection::parse)?.takeIf { door -> app.collections.all.any { it.collection == door } }?.let {
                app.collections.select(it)
                startActivity(Intent(this, DoorActivity::class.java))
            }
        }
        // What the account has up for sale sells while this screen is up: an offer that meets
        // the price is taken as it comes.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // News is only spent by a round that worked: one that failed is run again.
                var due = true
                var last = 0L
                while (true) {
                    if (guest.asks.isEmpty()) {
                        due = true
                        delay(IDLE_MS)
                        continue
                    }
                    try {
                        // With no news at all a round is still run now and then.
                        due = due || SystemClock.elapsedRealtime() - last > ROUND_MS || guest.news()
                        if (!due) continue
                        val sold = guest.tend()
                        due = false
                        last = SystemClock.elapsedRealtime()
                        if (sold.isNotEmpty()) {
                            say(getString(R.string.guest_sold))
                            refresh()
                        }
                    } catch (e: IOException) {
                        Log.e(TAG, "Could not look after what is for sale: ${e.message}")
                        delay(IDLE_MS)
                    } catch (e: TimeoutCancellationException) {
                        delay(IDLE_MS)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        given(intent)?.let { take(it, outside = true) }
    }

    /** What the account came with: a link it opened, or what a door said to a phone held to it. */
    private fun given(intent: Intent): String? = when (intent.action) {
        Intent.ACTION_VIEW -> intent.dataString
        NfcAdapter.ACTION_NDEF_DISCOVERED ->
            (IntentCompat.getParcelableArrayExtra(intent, NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage::class.java)
                ?.firstOrNull() as? NdefMessage)?.let { DoorTag.text(it.toByteArray()) }
        // Anything else that starts the app brings nothing with it.
        else -> null
    }

    override fun onStart() {
        super.onStart()
        if (!::binding.isInitialized) return
        // The page that does the work is in whichever of the account's screens is in front.
        guest.attach(binding.pages)
        app.sales.wake()
        say("")
        // A phone coming to a door shows what it knows. The site is asked only when it may know more.
        if (tab == Tab.NFTS && !guest.expecting) show() else refresh()
    }

    /**
     * Does what a code or a link asks for. What came from outside, a link another app opened
     * or a tag, was not chosen by whoever holds the phone as a scan is: it is asked about first.
     */
    private fun take(text: String, outside: Boolean = false) = took(text, null, { armed = it }, ::refresh, outside)

    override fun onDestroy() {
        // Whichever account's page was here: after a change of account it is not the one in use.
        if (::binding.isInitialized) for (account in app.accounts.all) app.guestOf(account).detach(binding.pages)
        super.onDestroy()
    }

    /**
     * What a door the phone is held to is given with nothing asked: the one ticket the account
     * holds for it. With several, or none the phone knows of, its holder is asked first.
     */
    private suspend fun answer(request: String): String? {
        val one = guest.atDoor(request)?.takeIf { it.sure }?.mine?.singleOrNull() ?: return null
        return guest.prove(request, listOf(one))
    }

    private fun heard(heard: Heard) {
        when (heard) {
            is Heard.Shown -> {
                say(getString(R.string.nft_shown, Entry.asked(heard.request)?.door.orEmpty()))
                Feedback.give(binding.root, Tone.GOOD, config.sound)
                show()
            }
            is Heard.Asked -> take(heard.request)
            is Heard.Lost -> say(heard.why)
        }
    }

    private enum class Tab(val label: Int) {
        NFTS(R.string.home_nfts),
        COLLECTIONS(R.string.home_collections),
        WALLET(R.string.home_wallet),
    }

    /** Which of the three is in view. The phone remembers, so the app opens where it was left. */
    private var tab: Tab
        get() = Tab.entries.firstOrNull { it.name == config.tab } ?: Tab.NFTS
        set(chosen) {
            config.tab = chosen.name
        }

    /** Something the account should know that belongs to no sheet: said above the foot, and gone with the next thing done. */
    private fun say(what: String) {
        binding.note.text = what
        binding.note.isVisible = what.isNotEmpty()
    }

    /** Shows what the phone knows, then asks the site and shows that. */
    private fun refresh() {
        show()
        lifecycleScope.launch {
            when (tab) {
                Tab.NFTS -> guest.mine()
                Tab.COLLECTIONS -> for (one in app.collections.all) app.sync(one.collection)
                Tab.WALLET -> count()
            }
            binding.refresh.isRefreshing = false
            show()
        }
    }

    /** Asks what the wallet holds, and what each collection made here has been paid. */
    private suspend fun count() {
        try {
            guest.wallet()
        } catch (e: IOException) {
            say(e.message.orEmpty())
        } catch (e: TimeoutCancellationException) {
            say(getString(R.string.guest_slow))
        }
        for (one in app.collections.all) if (app.owns(one.collection)) app.sales.count(one.collection)
    }

    private fun show() {
        val me = app.account
        binding.name.text = me.name
        binding.avatar.setImageDrawable(Identicon(me.mark, getColor(R.color.surface)))
        binding.avatar.clipToOutline = true
        val now = tab
        binding.tabs.removeAllViews()
        for (one in Tab.entries) {
            val pill = layoutInflater.inflate(R.layout.pill, binding.tabs, false) as TextView
            pill.setText(one.label)
            pill.isSelected = one == now
            pill.setOnClickListener {
                tab = one
                say("")
                refresh()
            }
            binding.tabs.addView(pill)
        }
        binding.tickets.isVisible = now == Tab.NFTS
        binding.collections.isVisible = now == Tab.COLLECTIONS
        binding.wallet.isVisible = now == Tab.WALLET
        binding.empty.isVisible = false
        when (now) {
            Tab.NFTS -> showNfts()
            Tab.COLLECTIONS -> showCollections()
            Tab.WALLET -> showWallet()
        }
    }

    private fun foot(second: Int, onSecond: () -> Unit, first: Int, onFirst: () -> Unit) {
        binding.second.setText(second)
        binding.second.setOnClickListener { onSecond() }
        binding.first.setText(first)
        binding.first.setOnClickListener { onFirst() }
    }

    private fun nothing(title: Int, help: Int) {
        binding.empty.isVisible = true
        binding.emptyTitle.setText(title)
        binding.emptyHelp.setText(help)
    }

    private fun heading(into: ViewGroup, text: Int) {
        val title = layoutInflater.inflate(R.layout.row_heading, into, false) as TextView
        title.setText(text)
        into.addView(title)
    }

    /**
     * The account's NFTs. One that says it is a ticket is listed with the tickets, in the order
     * of their numbers; the rest come after.
     */
    private fun showNfts() {
        foot(R.string.guest_find, { startActivity(Intent(this, CollectionActivity::class.java)) }, R.string.guest_scan) {
            scan.launch(Intent(this, ScanActivity::class.java))
        }
        val held = guest.held
        binding.tickets.removeAllViews()
        if (held.isEmpty()) nothing(R.string.guest_none, R.string.guest_none_help)
        val mine = guest.me ?: return
        val asks = guest.asks
        val all = held.map { Described(app, mine, it.h) }.sortedWith(Described.ORDER)
        val (tickets, others) = all.partition { it.about?.metadata?.ticket != null }
        for ((title, listed) in listOf(R.string.guest_tickets to tickets, R.string.guest_others to others)) {
            if (listed.isEmpty()) continue
            // A heading tells two kinds apart. An account with only one kind needs none.
            if (tickets.isNotEmpty() && others.isNotEmpty()) heading(binding.tickets, title)
            for (nft in listed) {
                val ticket = nft.ticket ?: continue
                val row = RowTicketBinding.inflate(layoutInflater, binding.tickets, true)
                row.picture.clipToOutline = true
                row.held.isVisible = false
                row.title.text = nft.lead
                row.detail.text = nft.rest.ifEmpty { "${ticket.h.take(8)}…${ticket.h.takeLast(4)}" }
                // The one thing an account's own list says about an NFT: that it is for sale.
                val asking = asks[ticket.id]
                row.state.isVisible = asking != null
                row.state.text = asking?.let { getString(R.string.guest_for_sale, PricesActivity.sats(it)) }
                row.state.setBackgroundResource(R.drawable.bg_badge_good)
                row.state.setTextColor(getColor(R.color.black))
                row.root.setOnClickListener {
                    startActivity(Intent(this, NftActivity::class.java).putExtra(NftActivity.EXTRA_H, ticket.h))
                }
                lifecycleScope.launch { row.picture.setImageBitmap(app.pictures.of(mine.site, ticket.h)) }
            }
        }
    }

    /**
     * What the account made, and the doors it keeps. Those it made, whose keys this phone holds,
     * come first and apart: they can be sold from and added to, the others only admitted.
     */
    private fun showCollections() {
        foot(
            R.string.home_add_door,
            { startActivity(Intent(this, CollectionActivity::class.java).putExtra(CollectionActivity.EXTRA_DOOR, true)) },
            R.string.home_create,
        ) { startActivity(Intent(this, MakeActivity::class.java)) }
        binding.collections.removeAllViews()
        val (mine, added) = app.collections.all.partition { app.owns(it.collection) }
        if (mine.isEmpty() && added.isEmpty()) return nothing(R.string.home_no_collections, R.string.home_no_collections_help)
        for ((title, some) in listOf(R.string.collections_mine to mine, R.string.collections_added to added)) {
            if (some.isEmpty()) continue
            heading(binding.collections, title)
            for (one in some) {
                val row = RowCollectionBinding.inflate(layoutInflater, binding.collections, true)
                val tally = app.tally(one.collection)
                val owned = app.owns(one.collection)
                row.name.text = one.name
                row.count.text = if (owned) getString(R.string.home_collection_yours, tally.all, tally.all - tally.held)
                else getString(R.string.collections_count, tally.admitted, tally.all)
                // What a collection made here is doing now: selling, or not yet priced.
                val plan = Prices(this, one.collection).plan
                row.chosen.isVisible = owned && plan.selling
                row.chosen.setText(R.string.home_on_sale)
                thumbs(row.thumbs, one.collection, Tickets(this, one.collection).all.take(THUMBS).map { it.h })
                row.root.setOnClickListener {
                    app.collections.select(one.collection)
                    startActivity(Intent(this, TicketsActivity::class.java))
                }
            }
        }
    }

    /**
     * The account's money: what it can spend at the mint in view, each of its mints with what is
     * there, what its collections have been paid and not yet handed over, and what has lately
     * come and gone. Sums at different mints are never added up: one of them is not money.
     */
    private fun showWallet() {
        foot(R.string.wallet_send, { sendSats(::refresh) }, R.string.wallet_receive) { receiveSats(::refresh) }
        val purse = guest.purse
        val viewing = guest.viewing
        val at = purse?.at(viewing)
        val test = at?.test ?: (viewing == Prices.TEST_MINT)
        binding.balance.text = PricesActivity.sats(at?.available ?: 0)
        binding.unit.setText(if (test) R.string.wallet_test_sats else R.string.wallet_sats)
        binding.at.text = getString(R.string.wallet_at, name(at?.name, viewing))
        binding.worthless.isVisible = test
        val tied = listOfNotNull(
            at?.locked?.takeIf { it > 0 }?.let { getString(R.string.wallet_locked, PricesActivity.sats(it)) },
            at?.pending?.takeIf { it > 0 }?.let { getString(R.string.wallet_pending, PricesActivity.sats(it)) },
            getString(R.string.wallet_reading).takeIf { purse == null },
        )
        binding.tied.isVisible = tied.isNotEmpty()
        binding.tied.text = tied.joinToString(" · ")
        binding.mints.removeAllViews()
        for (mint in purse?.mints.orEmpty()) {
            val row = RowMintBinding.inflate(layoutInflater, binding.mints, true)
            val chosen = mint.url.trimEnd('/') == viewing.trimEnd('/')
            row.name.text = name(mint.name, mint.url)
            row.test.isVisible = mint.test
            row.amount.text = PricesActivity.sats(mint.available)
            for (part in listOf(row.root, row.name, row.amount)) part.isSelected = chosen
            row.chosen.setImageResource(if (chosen) R.drawable.ic_check else R.drawable.ic_circle)
            row.root.setOnClickListener {
                guest.view(mint.url)
                say("")
                show()
            }
        }
        tills()
        binding.history.removeAllViews()
        // What was begun and undone never happened to the money: it is not listed.
        val past = purse?.history.orEmpty().filter { it.amount > 0 && it.state in OVER + UNDER_WAY }.take(RECENT)
        if (past.isNotEmpty()) heading(binding.history, R.string.wallet_recent)
        past.forEachIndexed { index, moved ->
            val row = RowMovedBinding.inflate(layoutInflater, binding.history, true)
            row.rule.isVisible = index > 0
            val (what, sign) = said(moved)
            row.what.text = if (moved.state in UNDER_WAY) getString(R.string.wallet_under_way, getString(what)) else getString(what)
            row.detail.text = getString(
                R.string.wallet_when, ago(moved.at * 1000),
                name(purse?.at(moved.mint)?.name, moved.mint),
            )
            row.amount.text = "$sign${PricesActivity.sats(moved.amount)}"
        }
    }

    /** How long ago, in words; what has only just happened is said to have. */
    private fun ago(at: Long): CharSequence =
        if (System.currentTimeMillis() - at < DateUtils.MINUTE_IN_MILLIS) getString(R.string.wallet_just_now)
        else DateUtils.getRelativeTimeSpanString(at, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)

    /** What a mint is called in a row: the site's name for it without its aside, or its address. */
    private fun name(named: String?, url: String): String =
        named?.substringBefore(" (")?.ifBlank { null } ?: url.substringAfter("://")

    /** What happened, in two words, and whether the wallet had more or less for it. */
    private fun said(moved: Moved): Pair<Int, String> = when (moved.type) {
        "mint" -> R.string.wallet_was_added to "+"
        "receive" -> R.string.wallet_was_received to "+"
        "melt" -> R.string.wallet_was_paid to "−"
        else -> R.string.wallet_was_sent to "−"
    }

    /**
     * What each collection made here has been paid and still holds. A collection has a key and a
     * purse of its own; one touch brings what is in it into the account's wallet, at the mint it
     * was paid at, which is where money is spent from.
     */
    private fun tills() {
        binding.tills.removeAllViews()
        val paid = app.collections.all.filter { app.owns(it.collection) }.mapNotNull { one ->
            val owed = app.sales.shops.value[one.collection.url]?.balance ?: 0
            val out = Prices(this, one.collection).takenOut != null
            if (owed > 0 || out) Triple(one, owed, out) else null
        }
        if (paid.isEmpty()) return
        heading(binding.tills, R.string.wallet_earned)
        paid.forEachIndexed { index, (one, owed, out) ->
            val row = RowMovedBinding.inflate(layoutInflater, binding.tills, true)
            row.rule.isVisible = index > 0
            row.what.text = one.name
            // Money already taken out of the collection, and not yet in the wallet, is finished first.
            row.detail.text = if (out) getString(R.string.wallet_earned_out)
            else getString(R.string.guest_sats, PricesActivity.sats(owed))
            row.amount.isVisible = false
            row.act.isVisible = true
            row.act.setText(R.string.wallet_move)
            row.act.setOnClickListener {
                row.act.isEnabled = false
                row.act.setText(R.string.wallet_moving)
                move(one)
            }
        }
    }

    /** Brings what a collection has been paid into the account's wallet, and says how it went. */
    private fun move(saved: Saved) {
        lifecycleScope.launch {
            say(
                try {
                    getString(R.string.wallet_moved, PricesActivity.sats(app.earned(saved)), saved.name)
                } catch (e: App.Spent) {
                    // Its mint says the ecash was taken in already. If its owner agrees, the kept copy goes.
                    confirm(getString(R.string.wallet_ecash_sure), e.message.orEmpty(), getString(R.string.wallet_ecash_delete), forGood = true) {
                        Prices(this@HomeActivity, saved.collection).takenOut = null
                        refresh()
                    }
                    ""
                } catch (e: IOException) {
                    e.message.orEmpty()
                }
            )
            count()
            show()
        }
    }

    /** Every account on the phone: which is in use, the way to another, and the way to a new one. */
    private fun accounts() {
        val sheet = SheetAccountsBinding.inflate(layoutInflater)
        val dialog = sheetOf(sheet.root)
        val using = app.account
        for (one in app.accounts.all) {
            val row = RowAccountBinding.inflate(layoutInflater, sheet.rows, true)
            val holds = app.guestOf(one).held.size
            val keeps = app.collectionsOf(one).all.size
            row.avatar.setImageDrawable(Identicon(one.mark, getColor(R.color.surface)))
            row.avatar.clipToOutline = true
            row.name.text = one.name
            row.detail.text = getString(
                R.string.accounts_has, resources.getQuantityString(R.plurals.collection_holds, holds, holds),
                resources.getQuantityString(R.plurals.accounts_collections, keeps, keeps),
            )
            val chosen = one.id == using.id
            for (part in listOf(row.root, row.name, row.detail)) part.isSelected = chosen
            row.chosen.setImageResource(if (chosen) R.drawable.ic_check else R.drawable.ic_circle)
            row.root.setOnClickListener {
                dialog.dismiss()
                if (!chosen) switchTo(one)
            }
        }
        sheet.add.setOnClickListener {
            dialog.dismiss()
            startActivity(Intent(this, WelcomeActivity::class.java).putExtra(WelcomeActivity.EXTRA_ANOTHER, true))
        }
        dialog.show()
    }

    /** Another account takes the phone. A door the last one had open is its own, and is closed. */
    private fun switchTo(account: Account) {
        app.accounts.use(account)
        config.doorOpen = null
        config.tab = Tab.NFTS.name
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private companion object {
        const val TAG = "HomeActivity"
        const val IDLE_MS = 20_000L
        const val ROUND_MS = 5 * 60_000L

        /** The pictures on a collection's row. */
        const val THUMBS = 3

        /** How many of the wallet's last doings are listed. */
        const val RECENT = 12

        /** How the wallet says something that happened to its money stands: done, or still on its way. */
        val OVER = setOf("finalized")
        val UNDER_WAY = setOf("pending", "executing")
    }
}
