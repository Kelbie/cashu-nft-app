package cash.nonfungible.app

import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import cash.nonfungible.app.databinding.ActivityDeleteBinding
import cash.nonfungible.app.databinding.CardDeleteBinding
import cash.nonfungible.app.databinding.RowDeleteBinding
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Tickets
import cash.nonfungible.app.entry.text
import cash.nonfungible.app.entry.whole
import cash.nonfungible.app.guest.Guest
import cash.nonfungible.app.studio.Destroyed
import cash.nonfungible.app.studio.Prices
import cash.nonfungible.app.studio.Sales
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Deletes an account, or everything on the phone, on a page that first shows exactly what that
 * is. Each account is a card: the NFTs it holds, which are burned; its sats, which would be
 * lost; the collections it made, whose unsold NFTs are burned and whose names the site keeps as
 * "[deleted]", because it has no way to remove one; and the doors it keeps, which are only
 * forgotten. What an account holds and what it made are one account, and go together.
 *
 * A key is all there is of what is under it, so nothing is deleted whose money could not be
 * counted, and nothing is lost that its owner was not shown. Sats that would go, and ecash taken
 * out and not yet said to be safe, are said in red with the way to move them out, and the button
 * cannot be held until there are none or their owner has said to delete them too. What was
 * agreed to is the sum that was shown: something that has come to hold more since is left alone.
 *
 * The button is held, not tapped. While it works each line says how far it has got. What could
 * not be deleted stays, with its key, says why, and can be tried again: opening this page again
 * shows what is left. When everything is gone the app is as new.
 */
class DeleteActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDeleteBinding
    private val app get() = application as App
    private var working = false

    /** One thing that will go, and the row that says what becomes of it. */
    private inner class Line(private val row: RowDeleteBinding) {
        fun say(detail: String, bad: Boolean = false) {
            row.detail.text = detail
            row.detail.setTextColor(getColor(if (bad) R.color.bad else R.color.muted))
        }

        fun working(detail: String) {
            mark(R.drawable.ic_working)
            say(detail)
        }

        fun done(detail: String) {
            mark(R.drawable.ic_check)
            row.act.isVisible = false
            say(detail)
        }

        fun failed(detail: String) {
            mark(R.drawable.ic_failed)
            say(detail, bad = true)
        }

        fun act(label: Int, act: () -> Unit) {
            row.act.isVisible = true
            row.act.setText(label)
            row.act.setOnClickListener { act() }
        }

        private fun mark(icon: Int) {
            row.mark.isVisible = true
            row.mark.setImageResource(icon)
        }
    }

    /**
     * An account, and the lines of its card: itself, each collection it made, each door it
     * keeps. Or, with no account, what this phone still holds a key to that no account lists.
     */
    private class Part(
        val account: Account?,
        val card: CardDeleteBinding,
        val self: Line?,
        val made: List<Pair<Saved, Line>>,
        val doors: List<Pair<Saved, Line>>,
    )

    /**
     * What something holds that would go with it: sats worth something, test sats worth
     * nothing, and whether ecash was taken out of it and is still kept here.
     */
    private class Stake(val real: Long, val test: Long, val token: Boolean)

    private var parts = emptyList<Part>()
    private var one: Account? = null

    /**
     * Collections this phone still holds the key of that no account lists: made here, and later
     * only taken off a list. Their NFTs and money are still under their keys, so when everything
     * is to go they are shown and go too. They are nobody's in particular, and are put on no list.
     */
    private var orphans = emptyList<Saved>()

    /**
     * What each account and each collection was found to hold, by the account's id or the
     * collection's address. One that is not here could not be counted, and is not deleted.
     */
    private val stakes = HashMap<String, Stake>()

    /** Whether everything has been asked about, so that what is at stake is known. */
    private var counted = false

    /** The lines that say what money would go: written again whenever the page comes back to the front. */
    private val money = ArrayList<View>()

    /** Whether what was asked for has been deleted: leaving the page then goes where the button does. */
    private var gone = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeleteBinding.inflate(layoutInflater)
        setContentView(binding.root)
        one = intent.getStringExtra(EXTRA_ACCOUNT)?.let { id -> app.accounts.all.firstOrNull { it.id == id } }
        binding.title.text = one?.let { getString(R.string.delete_title, it.name).removeSuffix("?") } ?: getString(R.string.delete_all_title)
        binding.intro.setText(if (one == null) R.string.delete_all_intro else R.string.delete_account_intro)
        binding.hold.text = getString(if (one == null) R.string.delete_all_hold else R.string.delete_hold)
        binding.hold.onHeld = ::begin
        binding.back.setOnClickListener { if (!working) leave() }
        // Once it has begun it is seen through: leaving would not stop what the site was asked.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!working) leave()
            }
        })
        binding.abandon.setOnCheckedChangeListener { _, _ -> ready() }
        binding.leave.setOnClickListener { home() }
        binding.again.setOnClickListener { recreate() }
        cards()
    }

    override fun onStart() {
        super.onStart()
        if (!working && !gone) count()
    }

    override fun onDestroy() {
        for (account in app.accounts.all) app.guestOf(account).detach(binding.pages)
        super.onDestroy()
    }

    private fun cards() {
        binding.cards.removeAllViews()
        money.clear()
        parts = (one?.let(::listOf) ?: app.accounts.all).map(::card) + listOfNotNull(kept())
    }

    /** The card for what no account lists, when there is any. */
    private fun kept(): Part? {
        if (orphans.isEmpty()) return null
        val card = CardDeleteBinding.inflate(layoutInflater, binding.cards, true)
        card.avatar.isVisible = false
        card.name.setText(R.string.delete_all_orphans)
        card.state.setText(R.string.delete_all_orphans_tag)
        return Part(
            null, card, null,
            orphans.map { saved ->
                val row = RowDeleteBinding.inflate(layoutInflater, card.rows, true)
                val listed = Tickets(this, saved.collection).all
                val still = listed.count { !it.sent }
                row.what.text = saved.name
                saved to Line(row).also { it.say(getString(R.string.delete_all_collection, still, listed.size - still, Sales.GONE)) }
            },
            emptyList(),
        )
    }

    /** An account's card: what it is called, and a line for everything of it that will go. */
    private fun card(account: Account): Part {
        val card = CardDeleteBinding.inflate(layoutInflater, binding.cards, true)
        card.avatar.setImageDrawable(Identicon(account.mark, getColor(R.color.surface)))
        card.avatar.clipToOutline = true
        card.name.text = account.name
        fun line(what: String, detail: String): Pair<Line, RowDeleteBinding> {
            val row = RowDeleteBinding.inflate(layoutInflater, card.rows, true)
            row.what.text = what
            return Line(row).also { it.say(detail) } to row
        }
        val guest = app.guestOf(account)
        val held = guest.held
        val (self, row) = line(
            if (held.isEmpty()) getString(R.string.delete_all_no_nfts) else resources.getQuantityString(R.plurals.delete_all_nfts, held.size, held.size),
            getString(if (guest.me == null) R.string.delete_all_unknown else R.string.delete_all_renamed, Sales.GONE),
        )
        guest.me?.let { thumbs(row.thumbs, it, held.take(THUMBS).map { nft -> nft.h }, 26) }
        val (made, doors) = app.collectionsOf(account).all.partition { it.mine }
        return Part(
            account, card, self,
            made.map { saved ->
                val listed = Tickets(this, saved.collection).all
                val kept = listed.count { !it.sent }
                saved to line(saved.name, getString(R.string.delete_all_collection, kept, listed.size - kept, Sales.GONE)).first
            },
            doors.map { saved -> saved to line(getString(R.string.delete_all_door, saved.name), getString(R.string.delete_all_door_help)).first },
        )
    }

    /**
     * Asks what there is to lose: in each account's wallet, and in what each collection has been
     * paid and still holds. Until that is known the button cannot be held, and what cannot be
     * asked about is not deleted.
     */
    private fun count() {
        counted = false
        stakes.clear()
        ready()
        lifecycleScope.launch {
            // Everything is to go: so is whatever this phone still holds a key to and no account lists.
            if (one == null && found()) cards()
            for (line in money) (line.parent as? ViewGroup)?.removeView(line)
            money.clear()
            for (part in parts) {
                val owner = part.account
                val self = part.self
                val guest = owner?.let(app::guestOf)
                if (owner != null && self != null && guest != null) try {
                    guest.attach(binding.pages)
                    val purse = if (guest.me == null) null else guest.wallet()
                    // What a payment in flight will give back is in no balance yet: it cannot be counted.
                    if ((purse?.settling ?: 0) > 0) throw IOException(getString(R.string.delete_all_settling))
                    var real = 0L
                    var test = 0L
                    for (at in purse?.mints.orEmpty()) {
                        val sum = at.available + at.locked + at.pending
                        if (sum == 0L) continue
                        if (at.test) test += sum else real += sum
                        lost(part, at.name.substringBefore(" ("), sum, at.test) { wallet(owner, guest, at.url) }
                    }
                    val token = guest.takenOut != null
                    if (token) token(part, getString(R.string.delete_all_token)) { wallet(owner, guest, null) }
                    stakes[owner.id] = Stake(real, test, token)
                } catch (e: IOException) {
                    self.say(getString(R.string.delete_all_unreached, e.message.orEmpty()), bad = true)
                } catch (e: TimeoutCancellationException) {
                    self.say(getString(R.string.delete_all_unreached, getString(R.string.guest_slow)), bad = true)
                }
                // Money in what nobody lists is moved out through the first account, which can be asked to take it in.
                val through = owner ?: app.accounts.all.firstOrNull()
                for ((saved, line) in part.made) {
                    try {
                        var real = 0L
                        var test = 0L
                        val purse = app.sales.purse(saved.collection)
                        if ((purse.get("settling").whole() ?: 0) > 0) throw IOException(getString(R.string.delete_all_settling))
                        for (at in (purse.get("mints") as? JsonArray)?.filterIsInstance<JsonObject>().orEmpty()) {
                            val sum = listOf("available", "locked", "pending").sumOf { at.get(it).whole() ?: 0 }
                            if (sum == 0L) continue
                            val worthless = at.get("test")?.takeIf { it.isJsonPrimitive }?.asBoolean == true
                            if (worthless) test += sum else real += sum
                            val where = getString(R.string.delete_all_till, saved.name, at.get("name").text().orEmpty().substringBefore(" ("))
                            lost(part, where, sum, worthless) { through?.let { wallet(it, app.guestOf(it), null) } }
                        }
                        val token = Prices(this@DeleteActivity, saved.collection).takenOut != null
                        if (token) token(part, getString(R.string.delete_all_token_of, saved.name)) { through?.let { wallet(it, app.guestOf(it), null) } }
                        stakes[saved.collection.url] = Stake(real, test, token)
                    } catch (e: IOException) {
                        line.say(getString(R.string.delete_all_unreached, e.message.orEmpty()), bad = true)
                    } catch (e: TimeoutCancellationException) {
                        line.say(getString(R.string.delete_all_unreached, getString(R.string.guest_slow)), bad = true)
                    }
                }
            }
            counted = true
            ready()
        }
    }

    /**
     * Looks for keys the site's page still holds on this phone that no account lists. Returns
     * whether what was found is other than what was known. A key the site has no page for has
     * nothing under it and is left out; one the site could not be asked about is kept in mind
     * all the same, by its address, so that it is seen and nothing is wiped around it.
     */
    private suspend fun found(): Boolean {
        val site = Config(this).site
        val known = app.accounts.all.flatMap { account ->
            listOfNotNull(app.guestOf(account).me?.pubkey) + app.collectionsOf(account).all.map { it.collection.pubkey }
        }.toSet()
        val held = try {
            app.sales.held(site)
        } catch (e: IOException) {
            return false
        } catch (e: TimeoutCancellationException) {
            return false
        }
        val now = held.filter { it !in known }.mapNotNull { pubkey ->
            val orphan = Collection.parse("$site/p/$pubkey") ?: return@mapNotNull null
            if (app.sales.exists(orphan) == false) return@mapNotNull null
            Saved(orphan, app.sync(orphan) ?: "${pubkey.take(8)}…${pubkey.takeLast(4)}", mine = true)
        }
        return (now != orphans).also { orphans = now }
    }

    /** A line for money that would go: how much and where, and for money that is worth something, the way to move it out. */
    private fun lost(part: Part, where: String, sats: Long, test: Boolean, out: () -> Unit) {
        val line = line(part, where)
        if (test) line.say(getString(R.string.delete_all_test, PricesActivity.sats(sats)))
        else {
            line.say(getString(R.string.delete_all_lost, PricesActivity.sats(sats)), bad = true)
            line.act(R.string.delete_all_move, out)
        }
    }

    /** A line for ecash that was taken out and is still kept here: deleting would lose the only copy. */
    private fun token(part: Part, where: String, out: () -> Unit) {
        val line = line(part, where)
        line.say(getString(R.string.delete_all_token_lost), bad = true)
        line.act(R.string.delete_all_move, out)
    }

    private fun line(part: Part, what: String): Line {
        val row = RowDeleteBinding.inflate(layoutInflater, part.card.rows, false)
        money += row.root
        // Under the account's own line, above its collections.
        part.card.rows.addView(row.root, if (part.self == null) 0 else 1)
        row.what.text = what
        return Line(row)
    }

    /** To the wallet the money is in, to move it out: that account's, showing that mint. */
    private fun wallet(account: Account, guest: Guest, mint: String?) {
        app.accounts.use(account)
        mint?.let(guest::view)
        Config(this).tab = "WALLET"
        Config(this).doorOpen = null
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Whether the button can be held: everything is counted, and there is nothing to lose or its owner has agreed to lose it. */
    private fun ready() {
        val real = stakes.values.sumOf { it.real }
        val tokens = stakes.values.any { it.token }
        val atStake = real > 0 || tokens
        binding.abandon.isVisible = !working && !gone && counted && atStake
        if (atStake) {
            binding.abandon.text = when {
                real > 0 && tokens -> getString(R.string.delete_all_abandon_both, PricesActivity.sats(real))
                tokens -> getString(R.string.delete_all_abandon_token)
                else -> getString(R.string.delete_all_abandon, PricesActivity.sats(real))
            }
        }
        binding.hold.isEnabled = counted && (!atStake || binding.abandon.isChecked)
        if (!working && !gone) say(if (counted) "" else getString(R.string.delete_all_counting))
    }

    private fun say(what: String, bad: Boolean = false) {
        binding.note.text = what
        binding.note.isVisible = what.isNotEmpty()
        binding.note.setTextColor(getColor(if (bad) R.color.bad else R.color.ink))
    }

    /**
     * Deletes, one account after another and within each its collections first: an account's own
     * key goes last, when nothing of it is left. Whatever fails is left as it is and said.
     */
    private fun begin() {
        if (!counted) return
        working = true
        val abandon = binding.abandon.isChecked
        binding.hold.isVisible = false
        binding.abandon.isVisible = false
        binding.back.isEnabled = false
        val all = parts.sumOf { (if (it.account == null) 0 else 1) + it.made.size + it.doors.size }
        var done = 0
        var stayed = 0
        fun progress() = say(getString(R.string.delete_all_progress, done, all))

        /** The sats of something its owner agreed to lose: the worthless ones always, the others if they said so. */
        fun lose(stake: Stake) = stake.test + if (abandon) stake.real else 0
        progress()
        lifecycleScope.launch {
            for (part in parts) {
                var left = false
                for ((saved, line) in part.made) {
                    val stake = stakes[saved.collection.url]
                    val went = if (stake == null) getString(R.string.delete_all_uncounted) else try {
                        line.working(getString(R.string.delete_unlisting))
                        app.sales.destroy(
                            saved.collection,
                            { taken -> if (taken > 0) line.working(getString(R.string.delete_unlisted, taken)) },
                            { burned, of -> line.working(getString(R.string.delete_burning, burned, of)) },
                            lose(stake), abandon && stake.token,
                        )
                    } catch (e: IOException) {
                        e.message.orEmpty()
                    } catch (e: TimeoutCancellationException) {
                        getString(R.string.guest_slow)
                    }
                    if (went is Destroyed && went.left == 0) {
                        // Nothing of it is left to sell or to keep a door for.
                        part.account?.let { app.forget(it, saved.collection) } ?: app.purge(saved.collection)
                        line.done(getString(R.string.delete_all_collection_done, went.burned, Sales.GONE))
                    } else {
                        left = true
                        stayed++
                        line.failed(
                            if (went is Destroyed) getString(R.string.delete_all_collection_left, went.left, went.problem.orEmpty())
                            else went.toString()
                        )
                    }
                    done++
                    progress()
                }
                for ((saved, line) in part.doors) {
                    part.account?.let { app.forget(it, saved.collection) }
                    line.done(getString(R.string.delete_removed))
                    done++
                    progress()
                }
                // What no account lists has no account to go after it.
                val owner = part.account ?: continue
                val self = part.self ?: continue
                val guest = app.guestOf(owner)
                val stake = stakes[owner.id]
                when {
                    // A collection that stayed keeps its account: it is the account's to try again.
                    left -> {
                        stayed++
                        self.failed(getString(R.string.delete_all_account_kept))
                    }
                    stake == null -> {
                        stayed++
                        self.failed(getString(R.string.delete_all_uncounted))
                    }
                    else -> {
                        self.working(getString(R.string.delete_unlisting))
                        val went = try {
                            guest.destroy(Sales.GONE, lose(stake), abandon && stake.token) { burned, of ->
                                self.working(getString(R.string.delete_burning, burned, of))
                            }
                        } catch (e: IOException) {
                            null to e.message
                        } catch (e: TimeoutCancellationException) {
                            null to getString(R.string.guest_slow)
                        }
                        if (went.first == 0) {
                            deleteSharedPreferences(owner.store("collections"))
                            app.accounts.remove(owner)
                            app.dropped(owner)
                            part.card.state.setText(R.string.delete_all_gone)
                            self.done(getString(R.string.delete_all_account_done))
                        } else {
                            stayed++
                            self.failed(
                                went.first?.let { getString(R.string.delete_all_collection_left, it, went.second.orEmpty()) } ?: went.second.orEmpty()
                            )
                        }
                    }
                }
                done++
                progress()
            }
            finished(stayed)
        }
    }

    /** It is over. With nothing left the phone is as new, or without the one account; with something left, that is said. */
    private fun finished(stayed: Int) {
        working = false
        binding.back.isEnabled = true
        binding.after.isVisible = true
        if (stayed > 0) return say(getString(R.string.delete_all_stayed), bad = true)
        // Said, and left on the page until its owner has read it: then the app goes on without what is gone.
        val everything = app.accounts.all.isEmpty()
        say(if (everything) getString(R.string.delete_all_done) else getString(R.string.delete_account_done, parts.mapNotNull { it.account?.name }.joinToString(", ")))
        binding.stays.isVisible = false
        binding.leave.isVisible = false
        binding.again.setText(R.string.guest_done)
        binding.again.setOnClickListener { leave() }
        gone = true
    }

    /** Back from the page: to where it was opened from, or, once things are gone, to what is left. */
    private fun leave() = when {
        !gone -> finish()
        app.accounts.all.isEmpty() -> wipe()
        else -> home()
    }

    private fun home() {
        Config(this).doorOpen = null
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /**
     * Nothing is anybody's any more: whatever else the phone kept goes too, and the app starts
     * again as it does the first time. A debug build stays pointed at the site it was testing on.
     *
     * The keys were the site's pages' to keep, and each was forgotten as what was under it went.
     * If the page still holds one, something is under it that this page never showed: the pages'
     * storage is then left as it is, and the key is found the next time this page is opened.
     */
    private fun wipe() {
        working = true
        binding.again.isEnabled = false
        lifecycleScope.launch {
            val config = Config(this@DeleteActivity)
            val (site, relays) = config.debugSite to config.debugRelays
            // A key the site says it has no page for has nothing under it. Any other is somebody's,
            // and so is one the site could not be asked about: not knowing is not the same as none.
            val keys = try {
                app.sales.held(config.site).count { pubkey ->
                    Collection.parse("${config.site}/p/$pubkey")?.let { app.sales.exists(it) } != false
                }
            } catch (e: IOException) {
                1
            } catch (e: TimeoutCancellationException) {
                1
            }
            if (keys > 0) {
                // Something is still under a key this page never deleted. Nothing more is wiped:
                // the page is shown again, where it will be found.
                working = false
                gone = false
                binding.again.isEnabled = true
                binding.again.setText(R.string.delete_all_again)
                binding.again.setOnClickListener { recreate() }
                return@launch say(getString(R.string.delete_all_keys_kept), bad = true)
            }
            val kept = File(applicationInfo.dataDir, "shared_prefs").list().orEmpty().map { it.removeSuffix(".xml") }
            for (name in kept) {
                if (OURS.none { name == it || name.startsWith("$it-") || name.startsWith("$it.") }) continue
                // Emptied and waited for, then deleted: something still on its way to the disk would
                // otherwise arrive after the file was gone, and bring it back.
                getSharedPreferences(name, MODE_PRIVATE).edit().clear().commit()
                deleteSharedPreferences(name)
            }
            // Written before the app stops, not after: a debug build must not wake up on the real site.
            if (BuildConfig.DEBUG && (site.isNotEmpty() || relays.isNotEmpty())) Config(this@DeleteActivity).keepTesting(site, relays)
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies(null)
            File(cacheDir, "nfts").deleteRecursively()
            File(filesDir, "templates").deleteRecursively()
            Log.i(TAG, "Everything was deleted")
            // The app as it was knows things that are no longer so. It starts again.
            startActivity(Intent.makeRestartActivityTask(ComponentName(this@DeleteActivity, HomeActivity::class.java)))
            Runtime.getRuntime().exit(0)
        }
    }

    companion object {
        /** The one account to delete. Without it, everything is. */
        const val EXTRA_ACCOUNT = "account"
        private const val TAG = "DeleteActivity"
        private const val THUMBS = 4

        /** What the app itself keeps, by the names it keeps it under. */
        private val OURS = listOf("accounts", "config", "guest", "collections", "tickets", "admitted", "made", "prices")
    }
}
