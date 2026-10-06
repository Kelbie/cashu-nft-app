package cash.nonfungible.app

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import cash.nonfungible.app.databinding.ActivityNftBinding
import cash.nonfungible.app.databinding.TileNftBinding
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Entry
import cash.nonfungible.app.entry.Ticket
import cash.nonfungible.app.guest.ForSale
import cash.nonfungible.app.tap.Heard
import cash.nonfungible.app.tap.Taps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * One NFT, on a page that takes its colours from the picture, with what is known about it
 * under the picture and what there is to do with it at the foot.
 *
 * One of the guest's own can be held to a door while the page is up: opening a ticket and
 * holding it out is the whole of getting in, and it needs no network. Under that are the door's
 * code, for a door there is nothing to hold a phone to, and selling it and sending it.
 *
 * Somebody else's, found by looking through a collection, can be looked at like any other. If
 * it is for sale, the one thing to do is buy it.
 */
class NftActivity : AppCompatActivity() {

    private lateinit var binding: ActivityNftBinding
    private lateinit var h: String
    private val app get() = application as App
    private val guest get() = app.guest

    /** The guest's own card of this NFT, when it is theirs. */
    private val ticket: Ticket? get() = guest.held.firstOrNull { it.h == h }

    /** Whether a door is shown every NFT the guest holds for it, or just this one. */
    private var together = false

    /** What a sheet has the guest's leave to answer a door with, while that sheet is up. */
    private var armed: Armed? = null
    private var settling: Job? = null
    private var taps: Taps? = null

    private val scan = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { read ->
        read.data?.getStringExtra(ScanActivity.EXTRA_TEXT)?.let { took(it, h, { armed = it }, ::show) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityNftBinding.inflate(layoutInflater)
        setContentView(binding.root)
        h = intent.getStringExtra(EXTRA_H) ?: return finish()
        // The guest's own is in their own collection. Anybody else's is in the one it was found in.
        val found = intent.getStringExtra(EXTRA_COLLECTION)?.let(Collection::parse)
        val collection = (if (ticket != null) guest.me else found) ?: return finish()
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            binding.body.updatePadding(top = bars.top + dp(12), bottom = bars.bottom + dp(16))
            insets
        }
        val nft = Described(app, collection, h)
        binding.headline.text = nft.lead
        binding.subline.text = nft.rest
        binding.subline.isVisible = nft.rest.isNotEmpty()
        val tile = TileNftBinding.inflate(layoutInflater, binding.tiles, true)
        tile.picture.clipToOutline = true
        tile.root.setOnClickListener { binding.scroll.turn() }
        lifecycleScope.launch {
            val picture = app.pictures.of(collection.site, h)
            tile.picture.setImageBitmap(picture)
            // What an NFT says of itself is known once its picture has been fetched, and so are its colours.
            val read = Described(app, collection, h)
            binding.headline.text = read.lead
            binding.subline.text = read.rest
            binding.subline.isVisible = read.rest.isNotEmpty()
            val look = picture?.let { withContext(Dispatchers.Default) { Look.of(it) } }
            dress(look)
            writeDetails(binding.about, collection, h, null, null, look)
        }
        // The picture has the whole page; what is known about the NFT waits under it.
        writeDetails(binding.about, collection, h, null, null, null)
        binding.scroll.addOnLayoutChangeListener { page, _, top, _, bottom, _, _, _, _ ->
            if (binding.focus.layoutParams.height != bottom - top) {
                binding.focus.layoutParams = binding.focus.layoutParams.apply { height = bottom - top }
                binding.scroll.rest = ((bottom - top) * UNDER).toInt()
                page.post { page.requestLayout() }
            }
        }
        binding.scroll.onRest = { under -> binding.details.setText(if (under) R.string.result_picture_back else R.string.result_details) }
        binding.details.setOnClickListener { binding.scroll.turn() }
        binding.back.setOnClickListener { finish() }
        binding.scan.setOnClickListener { scan.launch(Intent(this, ScanActivity::class.java)) }
        binding.sell.setOnClickListener { ticket?.let { sell(it, ::show) } }
        binding.send.setOnClickListener { ticket?.let { send(it, ::show) } }
        binding.group.setOnClickListener {
            together = !together
            show()
        }
        binding.dots.behindWords = true
        taps = Taps(this, ::heard) { request -> armed?.invoke(request) ?: answer(request) }
        intent.getStringExtra(EXTRA_LISTING)?.let(::offer)
    }

    override fun onStart() {
        super.onStart()
        if (!::h.isInitialized) return
        guest.attach(binding.pages)
        show()
    }

    override fun onDestroy() {
        if (::binding.isInitialized) guest.detach(binding.pages)
        super.onDestroy()
    }

    /**
     * What there is to do with it, which may have changed since the page was last looked at:
     * it is the guest's or it is not, and theirs is for sale or it is not.
     */
    private fun show() {
        val mine = ticket
        // One of the guest's own that is gone was sold or sent: there is nothing left to show.
        if (mine == null && intent.getStringExtra(EXTRA_COLLECTION) == null) return finish()
        binding.hold.isVisible = mine != null && taps?.ready == true
        binding.scan.isVisible = mine != null
        binding.sell.isVisible = mine != null
        binding.send.isVisible = mine != null
        binding.group.isVisible = false
        if (mine == null) return
        binding.seller.isVisible = false
        binding.buy.isVisible = false
        val asking = guest.asks[mine.id]
        binding.sell.text = asking?.let { getString(R.string.guest_for_sale, PricesActivity.sats(it)) } ?: getString(R.string.nft_sell)
        // The others this guest holds for the same door: a group that arrives together.
        val door = app.pictures.about(h)?.metadata?.takeIf { it.ticket != null }?.collection?.id
        val group = if (door == null) 1 else guest.held.count { app.pictures.about(it.h)?.metadata?.opens(door) == true }
        binding.group.isVisible = group > 1 && binding.hold.isVisible
        binding.group.text = if (together) getString(R.string.nft_group_all, group) else getString(R.string.nft_group_one, group)
    }

    /** Somebody has this NFT for sale: who, for what, and the button that buys it. */
    private fun offer(listing: String) {
        lifecycleScope.launch {
            val sale: ForSale = try {
                guest.listing(listing)
            } catch (e: IOException) {
                null
            }?.takeIf { it.h == h } ?: return@launch
            if (ticket != null) return@launch
            binding.seller.isVisible = true
            binding.seller.text = getString(R.string.event_resold, sale.sellerName.ifBlank { getString(R.string.event_from_host) })
            binding.buy.isVisible = true
            binding.buy.text = getString(R.string.nft_buy, PricesActivity.sats(sale.price))
            // Paying is a sheet of its own: an invoice to pay, and the wait for the NFT.
            binding.buy.setOnClickListener { buy(sale, ::show) }
        }
    }

    /** What a door the phone is held to is given: this NFT, or the guest's for that door together. */
    private suspend fun answer(request: String): String? {
        if (ticket == null) return null
        val door = guest.atDoor(request, h) ?: return null
        val shown = if (together) door.mine else door.mine.take(1)
        return if (shown.isEmpty()) null else guest.prove(request, shown)
    }

    private fun heard(heard: Heard) {
        settling?.cancel()
        when (heard) {
            is Heard.Shown -> {
                binding.status.text = getString(R.string.nft_shown, Entry.asked(heard.request)?.door.orEmpty())
                binding.tick.play()
                Feedback.give(binding.root, Tone.GOOD, Config(this).sound)
                show()
            }
            // Not a door this page can answer by itself: the guest is asked, as for a code.
            is Heard.Asked -> return took(heard.request, h, { armed = it }, ::show)
            is Heard.Lost -> binding.status.text = heard.why.ifEmpty { getString(R.string.nft_lost) }
        }
        settling = lifecycleScope.launch {
            delay(SETTLE_MS)
            binding.tick.isVisible = false
            binding.status.setText(R.string.nft_hold)
        }
    }

    /** Colours the page from the picture, as the door's page colours itself. */
    private fun dress(look: Look?) {
        if (look == null) return
        run {
            val from = (binding.ground.background as? ColorDrawable)?.color ?: look.ground
            ValueAnimator.ofObject(ArgbEvaluator(), from, look.ground).apply {
                duration = 220
                addUpdateListener { binding.ground.setBackgroundColor(it.animatedValue as Int) }
            }.start()
            for (text in listOf(binding.headline, binding.subline, binding.status, binding.group, binding.seller)) {
                text.setTextColor(look.ink)
            }
            binding.dots.ink = look.ink
            binding.tick.ink = Look.inkOn(getColor(R.color.lime))
            val bars = WindowCompat.getInsetsController(window, binding.root)
            bars.isAppearanceLightStatusBars = Look.isLight(look.ground)
            bars.isAppearanceLightNavigationBars = Look.isLight(look.ground)
            val card = binding.tiles.getChildAt(0)?.background?.mutate() as? LayerDrawable
            card?.findDrawableByLayerId(R.id.shadow)?.setTint(look.ink)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_H = "h"

        /** The collection an NFT that is not the guest's was found in. */
        const val EXTRA_COLLECTION = "collection"

        /** The site's listing of it, when it is for sale. */
        const val EXTRA_LISTING = "listing"
        private const val SETTLE_MS = 5000L

        /** How far down the page rests when what is known about an NFT is in view: its picture's foot stays above. */
        private const val UNDER = 0.56f
    }
}
