package cash.nonfungible.app

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.nfc.NfcAdapter
import android.nfc.cardemulation.CardEmulation
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import cash.nonfungible.app.databinding.ActivityDoorBinding
import cash.nonfungible.app.entry.Admitted
import cash.nonfungible.app.entry.Answer
import cash.nonfungible.app.entry.Asked
import cash.nonfungible.app.entry.Entry
import cash.nonfungible.app.entry.Rotation
import cash.nonfungible.app.entry.Taken
import cash.nonfungible.app.entry.Tickets
import cash.nonfungible.app.nostr.Nip19
import cash.nonfungible.app.nostr.Nip59
import cash.nonfungible.app.nostr.NostrEvent
import cash.nonfungible.app.nostr.NostrKeyPair
import cash.nonfungible.app.nostr.NostrWebSocketClient
import cash.nonfungible.app.tap.TagService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The door of one collection. A guest holds their phone to this one, which needs nothing of the
 * guest's phone but that it is there; or they scan the code this one shows, and their phone
 * answers over the network. Either way this phone asks the mint, and says who may come in.
 */
class DoorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDoorBinding
    private lateinit var config: Config
    private val app get() = application as App

    /** The codes the door is asking with, while it is open. */
    private var rotation: Rotation? = null
    private var listener: NostrWebSocketClient? = null
    private var ticking: Job? = null

    /** The collections whose page this screen has already gone to read for want of a list. */
    private val read = HashSet<String>()

    /** A word about the last answer, to go with the next code. */
    private var said: Int? = null

    /** Whether this screen has sent the doorkeeper to see a decision. */
    private var away = false

    /** Whether the door's own camera is reading a guest's code: the door stays open behind it. */
    private var scanning = false

    /** The events the relays have already delivered: each of them repeats the others. */
    private val seen = LinkedHashSet<String>()

    /** The answers from the network being checked now, and when the last few began. */
    private var quiet = 0
    private val began = ArrayDeque<Long>()

    private val result = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        // The doorkeeper is back, however quickly: the door opens again.
        away = false
        show(app.doorway.value)
    }

    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { read ->
        scanning = false
        read.data?.getStringExtra(ScanActivity.EXTRA_TEXT)?.let(::handed)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A door is some collection's. With none there is no door to be.
        if (app.collections.selected == null) return finish()
        binding = ActivityDoorBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // Nothing but the door: a reader can take the status bar's icons for part of a code.
        WindowCompat.getInsetsController(window, binding.root).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.statusBars())
        }
        config = Config(this)
        binding.dots.behindWords = true
        binding.settings.setOnClickListener { open(SettingsActivity::class.java) }
        // The door is closed by leaving it: back, or to the collection's own page.
        binding.back.setOnClickListener { leave() }
        binding.tickets.setOnClickListener { leave() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })
        binding.hand.setOnClickListener {
            val saved = app.collections.selected ?: return@setOnClickListener
            sellOrSend(saved.collection) {
                // Sold or sent, the collection holds one fewer: its page is read again.
                lifecycleScope.launch {
                    app.sync(saved.collection)
                    if (saved == app.collections.selected) count(saved)
                }
            }
        }
        // The three ways in. Two are what the door shows; the third is its own camera.
        binding.wayTap.setOnClickListener {
            // A phone that could be held to, with NFC switched off, is one switch from it.
            if (emulation() == null) return@setOnClickListener startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
            config.code = false
            show(app.doorway.value)
        }
        binding.wayCode.setOnClickListener {
            config.code = true
            show(app.doorway.value)
        }
        binding.wayScan.setOnClickListener {
            scanning = true
            scan.launch(Intent(this, ScanActivity::class.java))
        }
        // Whichever door screen is in front shows what is happening at the door.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { app.doorway.collect(::show) }
        }
    }

    override fun onStart() {
        away = false
        super.onStart()
        app.sales.wake()
    }

    override fun onResume() {
        super.onResume()
        if (!::binding.isInitialized) return
        // While the door is in front, a phone held to this one is ours to answer.
        val cards = emulation() ?: return
        cards.setPreferredService(this, ComponentName(this, TagService::class.java))
        // A door is held to and holds itself to nothing. Where the phone allows, it stops
        // looking for cards of its own, so that a guest's phone is never taken for one.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            try {
                NfcAdapter.getDefaultAdapter(this)
                    ?.setDiscoveryTechnology(this, NfcAdapter.FLAG_READER_DISABLE, NfcAdapter.FLAG_LISTEN_KEEP)
            } catch (e: UnsupportedOperationException) {
                Log.d(TAG, "This phone cannot be a card only: ${e.message}")
            }
        }
    }

    override fun onPause() {
        if (::binding.isInitialized) {
            emulation()?.unsetPreferredService(this)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                NfcAdapter.getDefaultAdapter(this)?.resetDiscoveryTechnology(this)
            }
        }
        super.onPause()
    }

    override fun onStop() {
        super.onStop()
        // The door closes when its screen is put away, but not for its own camera.
        if (!scanning) stopListening()
    }

    /** Whether this phone could be held to, were its NFC switched on. */
    private fun canTap(): Boolean = NfcAdapter.getDefaultAdapter(this) != null &&
        packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)

    /** What lets this phone answer as a card, if it can and is set to. */
    private fun emulation(): CardEmulation? {
        val nfc = NfcAdapter.getDefaultAdapter(this)?.takeIf { it.isEnabled } ?: return null
        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_NFC_HOST_CARD_EMULATION)) return null
        return CardEmulation.getInstance(nfc)
    }

    /** Puts the chosen collection on the door: where to hold a phone, its code, or the answer being checked. */
    private fun show(doorway: Doorway) {
        val saved = app.collections.selected
        val checking = doorway as? Doorway.Checking
        // A phone that cannot be held to shows its code. One that can shows whichever its keeper chose.
        val taps = emulation() != null
        val code = !taps || config.code
        binding.collection.text = saved?.name
        for (shown in listOf(binding.who, binding.code, binding.below)) {
            shown.isVisible = saved != null
        }
        binding.hand.isVisible = saved != null && app.owns(saved.collection)
        binding.tap.isVisible = checking == null && !code
        for (shown in listOf(binding.qr, binding.countdown, binding.next)) shown.isVisible = checking == null && code
        binding.status.isVisible = checking == null
        binding.ways.isVisible = checking == null
        // A phone with no NFC at all cannot be held to. One with it switched off can be, once it is on.
        binding.wayTap.isEnabled = taps || canTap()
        binding.wayTap.isSelected = !code
        binding.wayCode.isSelected = code
        binding.checking.isVisible = checking != null
        if (doorway is Doorway.Decided) {
            // Handing the decision on opens the door again, and this is called once more.
            app.doorway.value = Doorway.Open
            val arrival = doorway.arrival
            if (arrival.fake) {
                // No wallet sends a proof its mint disowns. It is not worth a screen.
                said = R.string.door_fake
            } else if (saved != null) {
                away = true
                result.launch(Intent(this, ResultActivity::class.java).putExtra(ResultActivity.EXTRA_ARRIVAL, arrival))
            }
            return
        }
        if (saved == null) return stopListening()
        count(saved)
        when (doorway) {
            is Doorway.Checking -> showChecking(saved, doorway.shown)
            // A screen that is on its way to show a decision does not open the door behind it.
            else -> if (!away) {
                if (rotation == null) listen(saved)
                binding.status.setText(said ?: if (!code) R.string.door_tap_help else if (!taps && canTap()) R.string.door_nfc_off else R.string.door_waiting)
                said = null
            }
        }
    }

    /**
     * An answer is being checked. The door says at once which NFT it shows and who is being
     * asked, so that nobody waits on a screen that says nothing.
     */
    private fun showChecking(saved: Saved, shown: List<String>) {
        val first = Described(app, saved.collection, shown.first())
        val host = saved.collection.site.substringAfter("://")
        binding.checkingPicture.clipToOutline = true
        binding.checkingPicture.setImageBitmap(app.pictures.held(first.h))
        binding.checkingName.text = when {
            shown.size > 1 -> getString(R.string.door_checking_group, shown.size)
            first.ticket == null -> getString(R.string.door_checking_unknown)
            else -> first.lead
        }
        binding.checkingNote.text = getString(R.string.door_checking, host)
    }

    /**
     * How many of the collection's NFTs are in, and the last few that came. A list never read
     * is read once.
     */
    private fun count(saved: Saved) {
        // Its mark first, as the site shows it, and its own picture over that once it is fetched.
        binding.avatar.setImageDrawable(Identicon(saved.collection.pubkey, getColor(R.color.surface)))
        lifecycleScope.launch {
            val picture = app.pictures.avatar(saved.collection) ?: return@launch
            if (app.collections.selected == saved) binding.avatar.setImageBitmap(picture)
        }
        val record = Admitted(this, saved.collection).all
        val listed = Tickets(this, saved.collection).all
        binding.count.text = getString(R.string.collections_count, listed.count { it.h in record }, listed.size)
        // The door's name, and for a collection made here how many have been sold or sent on.
        val sold = listed.count { it.sent }
        binding.door.text = if (app.owns(saved.collection)) getString(R.string.door_yours, config.door, sold, listed.size)
        else config.door
        binding.recent.removeAllViews()
        for ((h, _) in record.entries.sortedByDescending { it.value.at }.take(RECENT).reversed()) {
            val picture = app.pictures.held(h) ?: continue
            val thumb = ImageView(this)
            thumb.setBackgroundResource(R.drawable.bg_thumb)
            thumb.clipToOutline = true
            thumb.scaleType = ImageView.ScaleType.CENTER_CROP
            thumb.setImageBitmap(picture)
            val side = (30 * resources.displayMetrics.density).toInt()
            binding.recent.addView(thumb, LinearLayout.LayoutParams(side, side).apply {
                if (binding.recent.childCount > 0) marginStart = -side / 4
            })
        }
        if (listed.isNotEmpty() || !read.add(saved.collection.url)) return
        lifecycleScope.launch {
            app.sync(saved.collection)
            if (saved == app.collections.selected) count(saved)
        }
    }

    /**
     * Opens the door: a request that changes every half minute, as an authenticator's code
     * does, said to a phone held to this one and shown as a code. One nostr key serves for as
     * long as the door is on screen, so the relays are joined once and an answer that is on its
     * way while another is checked is not lost; what changes is the request's id.
     */
    private fun listen(saved: Saved) {
        val key = NostrKeyPair.generate()
        val relays = config.relays
        val nprofile = Nip19.encodeNprofile(key.publicKeyBytes, relays)
        val codes = Rotation { Entry.request(saved.collection, config.door, nprofile) }
        rotation = codes
        // Relays repeat each other and anyone can write to the key on screen. The door looks
        // once at each event, and only at one that answers one of its codes.
        listener = NostrWebSocketClient(SOCKETS, relays, key.hexPub) { _, event ->
            val answer = read(event, key) ?: return@NostrWebSocketClient
            runOnUiThread { answered(answer, codes) }
        }.also(NostrWebSocketClient::start)
        app.tag.onWritten = { text -> runOnUiThread { handed(text) } }
        ticking?.cancel()
        ticking = lifecycleScope.launch {
            var shown: Asked? = null
            while (true) {
                val now = SystemClock.elapsedRealtime()
                val code = codes.current(now)
                if (code !== shown) {
                    shown = code
                    if (BuildConfig.DEBUG) Log.d(TAG, "Showing request ${code.encoded}")
                    app.tag.text = code.encoded
                    binding.qr.setImageDrawable(QrCode(code.encoded))
                    // The new code fades in, so a change is seen as one.
                    binding.qr.alpha = 0.2f
                    binding.qr.animate().alpha(1f).setDuration(250).start()
                }
                val left = codes.left(now)
                binding.countdown.progress = (left * binding.countdown.max / codes.periodMs).toInt()
                binding.next.text = getString(R.string.door_next, (left + 999) / 1000)
                delay(TICK_MS)
            }
        }
    }

    /** The answer an event carries, and the key that signed it: a holder may say who they are. */
    private fun read(event: NostrEvent, key: NostrKeyPair): Answer? {
        synchronized(seen) {
            if (!seen.add(event.id.orEmpty())) return null
            if (seen.size > REMEMBERED) seen.remove(seen.first())
        }
        return try {
            val sent = Nip59.unwrapGiftWrappedDm(event, key.secretKeyBytes)
            Entry.answer(sent.rumor.content, sent.seal.pubkey)
        } catch (e: Exception) {
            Log.d(TAG, "Ignored an event that does not unwrap")
            null
        }
    }

    /**
     * An answer came over the network. Anybody who has seen the code can send one, so nothing
     * shows and no code is spent until the mint bears it out, and only so many are looked at in
     * a minute: a door that is being pestered still has its whole patience for a phone held to it.
     */
    private fun answered(answer: Answer, codes: Rotation) {
        if (codes !== rotation) return
        val now = SystemClock.elapsedRealtime()
        when (val matched = codes.match(answer, now)) {
            is Taken.Live -> {
                began.removeAll { now - it > MINUTE_MS }
                if (quiet >= AT_ONCE || began.size >= A_MINUTE) return
                began.addLast(now)
                quiet++
                val spend = { codes.take(answer, SystemClock.elapsedRealtime()) is Taken.Live }
                app.checkQuietly(answer, matched.asked, spend) { quiet-- }
            }
            Taken.Late -> binding.status.setText(R.string.door_expired)
            Taken.None -> Unit
        }
    }

    /** A guest handed their answer to the door itself: held their phone to it, or showed it a code. */
    private fun handed(text: String) {
        val codes = rotation ?: return
        val direct = Entry.direct(text) ?: return binding.status.setText(R.string.door_not_answer)
        val asked = codes.shown(direct.id) ?: return binding.status.setText(R.string.door_expired)
        val answer = direct.to(asked)
        when (val taken = codes.take(answer, SystemClock.elapsedRealtime())) {
            is Taken.Live -> app.check(answer, taken.asked)
            Taken.Late -> binding.status.setText(R.string.door_expired)
            Taken.None -> Unit
        }
    }

    private fun stopListening() {
        rotation = null
        ticking?.cancel()
        ticking = null
        listener?.stop()
        listener = null
        app.tag.text = null
        app.tag.onWritten = null
    }

    private fun open(screen: Class<*>) {
        startActivity(Intent(this, screen))
    }

    /** Closes the door for good, and not just until the app is opened again. */
    private fun leave() {
        config.doorOpen = null
        finish()
    }

    companion object {
        private const val TAG = "DoorActivity"
        private const val TICK_MS = 100L
        private const val RECENT = 3
        private const val REMEMBERED = 512

        // How many answers from the network are checked at once, and how many in a minute.
        private const val AT_ONCE = 3
        private const val A_MINUTE = 24
        private const val MINUTE_MS = 60_000L

        // A door can wait a long time between answers: pings notice a relay that went quiet,
        // and the listener then connects again.
        private val SOCKETS = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    }
}
