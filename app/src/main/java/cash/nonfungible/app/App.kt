package cash.nonfungible.app

import android.app.Application
import android.util.Log
import cash.nonfungible.app.entry.Admitted
import cash.nonfungible.app.entry.Answer
import cash.nonfungible.app.entry.Asked
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Arrival
import cash.nonfungible.app.entry.Decision
import cash.nonfungible.app.entry.Door
import cash.nonfungible.app.entry.Refusal
import cash.nonfungible.app.entry.Tickets
import cash.nonfungible.app.entry.Verdict
import cash.nonfungible.app.guest.Guest
import cash.nonfungible.app.studio.Made
import cash.nonfungible.app.studio.Prices
import cash.nonfungible.app.studio.Sales
import cash.nonfungible.app.tap.DoorTag
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** A collection in numbers: how many NFTs it lists, how many have come in, how many it still holds. */
data class Tally(val all: Int, val admitted: Int, val held: Int)

/** What is happening at the door: it shows its code until an answer comes, then checks it. */
sealed interface Doorway {
    data object Open : Doorway

    /** An answer is being checked: the NFTs it shows. */
    class Checking(val shown: List<String>) : Doorway

    /** A decision no screen has shown yet. */
    class Decided(val arrival: Arrival) : Doorway
}

/** What outlives a screen: what the phone keeps, and a check that is under way. */
class App : Application() {
    val accounts by lazy { Accounts(this) }
    val door by lazy { Door(this) }
    val pictures by lazy { Pictures(this) }
    val sales by lazy { Sales(this) }

    /**
     * The account in use. Before anybody has named one it is the first, with no name yet: a
     * link that opens the app is read for somebody, whoever they turn out to be.
     */
    val account: Account get() = accounts.active ?: Account(Account.FIRST, "")

    /** What the account in use keeps doors for and has made, and what it holds and can spend. */
    val collections: Collections get() = collectionsOf(account)
    val guest: Guest get() = guestOf(account)

    private val keeping = HashMap<String, Collections>()
    private val holding = HashMap<String, Guest>()

    @Synchronized
    fun collectionsOf(account: Account): Collections = keeping.getOrPut(account.id) { Collections(this, account) }

    @Synchronized
    fun guestOf(account: Account): Guest = holding.getOrPut(account.id) { Guest(this, account) }

    /** Every collection any account on this phone keeps: a shop is kept whoever is looking. */
    val everyCollection: List<Saved> get() = accounts.all.ifEmpty { listOf(account) }.flatMap { collectionsOf(it).all }.distinctBy { it.collection }

    /**
     * Brings what a collection has been paid into the wallet of the account in use, and says how
     * many sats came. It leaves the collection as ecash, which for a moment is all there is of
     * it: that is kept until the wallet has it, so a move that is cut short is finished the next
     * time it is asked for. Throws with nothing moved, or with the ecash kept, and says which.
     * Ecash is never let go of here: if its mint says it was taken in already, that is thrown
     * as [Spent] for its owner to decide.
     */
    suspend fun earned(saved: Saved): Long = moving.withLock {
        // Whose wallet it goes to is settled now: the account in use may change while this works.
        val into = guest
        val prices = Prices(this, saved.collection)
        var moved = 0L
        // A collection may have been paid at more than one mint: each is brought in, one after another.
        for (round in 0 until MINTS) {
            val token = prices.takenOut ?: sales.cashOut(saved.collection) ?: break
            try {
                moved += into.receive(token).second
            } catch (e: IOException) {
                // The mint says it has been taken in already. By a move whose answer was lost,
                // most likely; but only its owner can say so, and the copy is kept until they do.
                if (e.message == SPENT) throw Spent(getString(R.string.wallet_move_spent, saved.name))
                throw IOException(getString(R.string.wallet_move_kept, e.message.orEmpty()))
            } catch (e: TimeoutCancellationException) {
                throw IOException(getString(R.string.wallet_move_kept, getString(R.string.guest_slow)))
            }
            prices.takenOut = null
        }
        if (moved == 0L) throw IOException(getString(R.string.wallet_move_failed, saved.name))
        moved
    }

    /** One move of earnings at a time, whichever screen asked: two would each take a token out and keep one. */
    private val moving = Mutex()

    /** Ecash a collection has out that its mint says was taken in already. */
    class Spent(message: String) : IOException(message)

    /** An account is gone: what the phone held in memory for it is let go. */
    @Synchronized
    fun dropped(account: Account) {
        keeping.remove(account.id)
        holding.remove(account.id)?.close()
    }

    /** The door as something a phone can be held to. It says nothing while no door is open. */
    val tag by lazy { DoorTag(packageName) }

    /** How a collection stands: its NFTs, those that have come in, and those it has not passed on. */
    fun tally(collection: Collection): Tally {
        val admitted = Admitted(this, collection).all
        val tickets = Tickets(this, collection).all
        return Tally(tickets.size, tickets.count { it.h in admitted }, tickets.count { !it.sent })
    }

    /**
     * Whether the account in use made the collection and so holds its key: the NFTs the
     * collection has not passed on are then its owner's to give or sell. Another account on the
     * same phone that only keeps a door for it does not own it.
     */
    fun owns(collection: Collection): Boolean = collections.all.any { it.collection == collection && it.mine }

    /**
     * An account stops keeping a collection. What the phone keeps about the collection itself,
     * its NFTs, who has come in, how it was made and its prices, goes only when no account on
     * the phone keeps it any more.
     */
    fun forget(account: Account, collection: Collection) {
        collectionsOf(account).remove(collection)
        purge(collection)
    }

    /** Deletes what the phone keeps about a collection itself, unless some account still keeps the collection. */
    fun purge(collection: Collection) {
        if (accounts.all.any { other -> collectionsOf(other).all.any { it.collection == collection } }) return
        Tickets(this, collection).delete()
        Admitted(this, collection).delete()
        Made(this, collection).delete()
        Prices(this, collection).delete()
    }

    /** What is happening at the door. */
    val doorway = MutableStateFlow<Doorway>(Doorway.Open)

    private val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Checks an answer a phone handed to the door itself, by a tap or as a code the door read.
     * Somebody is standing there, so the door says at once that it is checking. A check that
     * has begun records its admissions whatever becomes of the screen.
     */
    fun check(answer: Answer, asked: Asked) {
        if (!begin(answer)) return
        work.launch { doorway.value = Doorway.Decided(decided(answer, asked) { true } ?: return@launch) }
    }

    /**
     * Checks an answer that came over the network. Anybody who can see the door's code can send
     * one, so the door does nothing that shows until the mint has borne a proof out: `spend` is
     * asked then, and says whether the code it answers was still there to be spent. An answer
     * the mint disowns comes to nothing, and `done` hears that it was real or was not.
     */
    fun checkQuietly(answer: Answer, asked: Asked, spend: () -> Boolean, done: (real: Boolean) -> Unit) {
        work.launch {
            val arrival = decided(answer, asked) { spend() && begin(answer) }
            val real = arrival != null && !arrival.fake
            if (real) doorway.value = Doorway.Decided(arrival ?: return@launch)
            withContext(Dispatchers.Main) { done(real) }
        }
    }

    /** Has the door say it is checking, unless it is already checking somebody else's. */
    @Synchronized
    private fun begin(answer: Answer): Boolean {
        if (doorway.value !is Doorway.Open) return false
        doorway.value = Doorway.Checking(answer.showings.map { it.assetHash })
        return true
    }

    private fun decided(answer: Answer, asked: Asked, go: () -> Boolean): Arrival? = try {
        door.decide(answer, asked, go)
    } catch (e: RuntimeException) {
        Log.e(TAG, "A check failed", e)
        val unchecked = Verdict.Refused(Refusal.NOT_CHECKED)
        Arrival(asked.collection.url, answer.showings.map { Decision(it.assetHash, unchecked) })
    }

    /**
     * Reads a collection's page and then fetches the pictures the phone lacks, so that the
     * door has them when their holders arrive. Returns the collection's name, or null.
     */
    suspend fun sync(collection: Collection): String? {
        val name = withContext(Dispatchers.IO) { door.sync(collection) }
        // One fetching of pictures at a time: a site allows one address only so much in a minute.
        if (warming?.isActive != true) {
            warming = work.launch { pictures.warm(collection.site, Tickets(this@App, collection).all.map { it.h }) }
        }
        return name
    }

    private var warming: Job? = null

    private companion object {
        const val TAG = "App"

        /** More mints than a collection is ever paid at. */
        const val MINTS = 6

        /** What the page says of ecash that has been taken in already. */
        const val SPENT = "This ecash has already been taken in."
    }
}
