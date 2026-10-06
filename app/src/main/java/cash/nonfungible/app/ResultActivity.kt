package cash.nonfungible.app

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.text.format.DateUtils
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import cash.nonfungible.app.databinding.ActivityResultBinding
import cash.nonfungible.app.databinding.TileNftBinding
import cash.nonfungible.app.entry.Admission
import cash.nonfungible.app.entry.Admitted
import cash.nonfungible.app.entry.Arrival
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Holder
import cash.nonfungible.app.entry.Refusal
import cash.nonfungible.app.entry.Tickets
import cash.nonfungible.app.entry.Verdict
import cash.nonfungible.app.guest.Guest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shows what the door decided about an answer, or one NFT opened from the list. The answer is
 * a band in the one colour that means it; under it the page takes its colours from the NFT.
 */
class ResultActivity : AppCompatActivity() {

    private lateinit var binding: ActivityResultBinding
    private lateinit var collection: Collection
    private val app get() = application as App
    private var returning: Job? = null

    /** An NFT on the page, and what was decided about it: nothing yet for one not let in. */
    private class Shown(val h: String, val verdict: Verdict?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityResultBinding.inflate(layoutInflater)
        setContentView(binding.root)
        // The band runs up under the status bar, so its colour is the first thing seen.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            binding.band.updatePadding(top = bars.top + dp(14))
            binding.bar.updatePadding(top = bars.top + dp(12))
            binding.body.updatePadding(bottom = bars.bottom + dp(16))
            insets
        }
        val arrival = IntentCompat.getParcelableExtra(intent, EXTRA_ARRIVAL, Arrival::class.java)
        val viewed = intent.getStringExtra(EXTRA_TICKET)
        val address = arrival?.collection ?: intent.getStringExtra(EXTRA_COLLECTION).orEmpty()
        collection = Collection.parse(address) ?: return finish()
        binding.next.setOnClickListener { finish() }
        binding.back.setOnClickListener { finish() }
        // The picture has the whole page; what is known about the NFT waits under it.
        binding.scroll.addOnLayoutChangeListener { page, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop || binding.focus.layoutParams.height != bottom - top) {
                binding.focus.layoutParams = binding.focus.layoutParams.apply { height = bottom - top }
                if (binding.about.root.isVisible) binding.scroll.rest = ((bottom - top) * UNDER).toInt()
                page.post { page.requestLayout() }
            }
        }
        binding.scroll.onRest = { under -> binding.details.setText(if (under) R.string.result_picture_back else R.string.result_details) }
        binding.details.setOnClickListener { binding.scroll.turn() }
        when {
            arrival != null -> arrived(arrival, fresh = savedInstanceState == null)
            viewed != null -> view(viewed, fresh = false)
            else -> finish()
        }
    }

    /** What the door decided about the NFTs somebody just showed it. */
    private fun arrived(arrival: Arrival, fresh: Boolean) {
        val decisions = arrival.decisions
        val admitted = decisions.filter { it.verdict is Verdict.Admitted }
        val twice = decisions.count { it.verdict is Verdict.AlreadyIn }
        val refused = decisions.size - admitted.size - twice
        val tone = when {
            admitted.isNotEmpty() -> Tone.GOOD
            twice > 0 -> Tone.TWICE
            else -> Tone.BAD
        }
        val one = decisions.singleOrNull()
        val greeting = arrival.holder?.name?.takeUnless { it.isBlank() || it in UNNAMED }
            ?.let { getString(R.string.result_welcome, it) }
        val word = when {
            one == null && tone == Tone.GOOD -> resources.getQuantityString(
                R.plurals.result_admitted_count, admitted.size, admitted.size
            )
            tone == Tone.GOOD -> getString(R.string.tickets_admitted)
            tone == Tone.TWICE -> getString(R.string.result_already_in)
            else -> getString(R.string.result_refused)
        }
        band(tone, word, getString(R.string.result_at, Config(this).door, time(System.currentTimeMillis())))
        if (one == null) {
            val counts = listOfNotNull(
                admitted.size.takeIf { it > 0 }?.let { getString(R.string.result_count_admitted, it) },
                twice.takeIf { it > 0 }?.let { getString(R.string.result_count_already_in, it) },
                refused.takeIf { it > 0 }?.let { getString(R.string.result_count_refused, it) },
            ).joinToString(" · ")
            say(
                greeting ?: getString(R.string.result_group, decisions.size),
                getString(R.string.result_group_help, counts)
            )
        } else {
            val nft = Described(app, collection, one.h)
            when (val verdict = one.verdict) {
                Verdict.Admitted -> say(
                    greeting ?: nft.lead,
                    listOfNotNull(nft.lead.takeIf { greeting != null }, nft.rest.ifEmpty { null })
                        .joinToString(" · ")
                )
                is Verdict.AlreadyIn -> say(
                    getString(R.string.result_came_in, ago(verdict.earlier)),
                    getString(
                        R.string.result_came_in_at,
                        nft.lead, verdict.earlier.door, time(verdict.earlier.at * 1000)
                    )
                )
                is Verdict.Refused -> say(getString(headline(verdict.reason)), reason(verdict.reason, one.h))
            }
        }
        show(decisions.map { Shown(it.h, it.verdict) }, arrival.holder, celebrate = fresh && tone == Tone.GOOD)
        // One NFT has its details under it. Each of a group has its own, a touch away.
        about(one?.h, one?.verdict, arrival.holder)
        if (admitted.isNotEmpty()) {
            val undo = resources.getQuantityString(R.plurals.result_undo, admitted.size, admitted.size)
            other(undo) {
                val record = Admitted(this, collection)
                admitted.forEach { record.forget(it.h) }
                finish()
            }
        }
        // A door that could not ask is still a door: its keeper may let the guest in themselves.
        val unchecked = decisions.filter { (it.verdict as? Verdict.Refused)?.reason == Refusal.NOT_CHECKED }
        if (admitted.isEmpty() && unchecked.isNotEmpty()) {
            other(getString(R.string.result_admit)) {
                val record = Admitted(this, collection)
                unchecked.forEach { record.record(it.h, getString(R.string.result_by_hand, Config(this).door)) }
                finish()
            }
        }
        val config = Config(this)
        // One guest let in needs nothing from the doorkeeper: the door comes back by itself.
        // A group stays, to be counted through the door.
        if (config.express && one?.verdict is Verdict.Admitted) returnSoon()
        if (fresh) Feedback.give(binding.root, tone, config.sound)
    }

    /** One NFT opened from the list: whether it has come in, and a way to change that by hand. */
    private fun view(h: String, fresh: Boolean) {
        val record = Admitted(this, collection)
        val at = record.all[h]
        val nft = Described(app, collection, h)
        // An NFT of a collection made here is its owner's until it is given or sold.
        val ticket = nft.ticket
        val yours = app.owns(collection) && ticket != null && !ticket.sent
        val whose = when {
            !app.owns(collection) || ticket == null -> collection.site.substringAfter("://")
            yours -> getString(R.string.result_yours)
            else -> getString(R.string.result_out)
        }
        // Nothing has been decided about it: there is no answer to put over it. How it stands is
        // said under its name, as on any NFT's page, and what can be done is at the foot.
        binding.band.isVisible = false
        binding.rule.isVisible = false
        binding.bar.isVisible = true
        val stands = if (at == null) {
            other(getString(R.string.result_admit)) {
                record.record(h, getString(R.string.result_by_hand, Config(this).door))
                view(h, fresh = true)
            }
            getString(R.string.result_waiting)
        } else {
            other(resources.getQuantityString(R.plurals.result_undo, 1, 1)) {
                record.forget(h)
                view(h, fresh = false)
            }
            getString(R.string.result_stands, getString(R.string.tickets_admitted), getString(R.string.result_at, at.door, time(at.at * 1000)))
        }
        say(nft.lead, getString(R.string.result_stands, stands, whose))
        val verdict = at?.let { Verdict.Admitted }
        show(listOf(Shown(h, verdict)), holder = null, celebrate = fresh)
        about(h, verdict, null)
        if (yours && ticket != null) {
            binding.next.setText(R.string.result_hand)
            binding.next.setOnClickListener {
                // Given or sold, it may be somebody else's when the sheet closes: look again.
                handOver(collection, ticket.id, h, ticket.title) {
                    lifecycleScope.launch {
                        app.sync(collection)
                        if (!isFinishing) view(h, fresh = false)
                    }
                }
            }
        } else {
            binding.next.setText(R.string.result_done)
            binding.next.setOnClickListener { finish() }
        }
        if (fresh) Feedback.give(binding.root, Tone.GOOD, Config(this).sound)
    }

    /** Writes what is known about the one NFT on the page under its picture, or nothing under a group. */
    private fun about(h: String?, verdict: Verdict?, holder: Holder?) {
        binding.details.isVisible = h != null
        binding.about.root.isVisible = h != null
        if (h == null) {
            binding.scroll.rest = 0
            return
        }
        described = Triple(h, verdict, holder)
        writeDetails(binding.about, collection, h, verdict, holder, worn)
        binding.scroll.rest = (binding.scroll.height * UNDER).toInt()
    }

    /** The one NFT whose details are under its picture, and the colours the page took from it. */
    private var described: Triple<String, Verdict?, Holder?>? = null
    private var worn: Look? = null

    private fun band(tone: Tone, word: String, at: String) {
        val ink = getColor(tone.ink)
        binding.band.setBackgroundColor(getColor(tone.color))
        binding.verdict.text = word
        binding.verdict.setTextColor(ink)
        binding.at.text = at
        binding.at.setTextColor(ink)
        WindowCompat.getInsetsController(window, binding.root).isAppearanceLightStatusBars =
            Look.isLight(getColor(tone.color))
    }

    private fun say(headline: String, subline: String) {
        binding.headline.text = headline
        binding.subline.text = subline
        binding.subline.isVisible = subline.isNotEmpty()
    }

    private fun other(label: String, act: () -> Unit) {
        binding.other.text = label
        binding.other.isVisible = true
        binding.other.setOnClickListener { act() }
    }

    /** Puts the NFTs on the page. One that was refused is shown without its colour. */
    private fun show(shown: List<Shown>, holder: Holder?, celebrate: Boolean) {
        binding.tiles.removeAllViews()
        var lead = true
        shown.forEachIndexed { index, nft ->
            val tile = TileNftBinding.inflate(layoutInflater, binding.tiles, true)
            val refused = nft.verdict is Verdict.Refused
            tile.picture.clipToOutline = true
            if (refused) {
                tile.picture.colorFilter = GREY
                tile.picture.alpha = 0.75f
            }
            if (shown.size > 1) badge(tile, nft.verdict)
            tile.root.setOnClickListener {
                if (shown.size == 1) binding.scroll.turn() else showDetails(collection, nft.h, nft.verdict, holder)
            }
            // The page takes its colours from the first NFT that is welcome on it.
            val leads = !refused && lead
            if (leads) lead = false
            if (celebrate) pop(tile.root, index)
            lifecycleScope.launch {
                val picture = app.pictures.of(collection.site, nft.h)
                tile.picture.setImageBitmap(picture)
                if (leads) dress(picture, celebrate)
            }
        }
        if (lead) dress(null, celebrate = false)
    }

    private fun badge(tile: TileNftBinding, verdict: Verdict?) {
        val (text, tone) = when (verdict) {
            is Verdict.Refused -> R.string.result_refused to Tone.BAD
            is Verdict.AlreadyIn -> R.string.result_already_in to Tone.TWICE
            else -> R.string.tickets_admitted to Tone.GOOD
        }
        tile.badge.setText(text)
        tile.badge.setTextColor(getColor(tone.ink))
        tile.badge.backgroundTintList = ColorStateList.valueOf(getColor(tone.color))
        tile.badge.isVisible = true
    }

    /** Colours the page from a picture and, for somebody just let in, throws paper. */
    private fun dress(picture: Bitmap?, celebrate: Boolean) {
        lifecycleScope.launch {
            val look = picture?.let { withContext(Dispatchers.Default) { Look.of(it) } }
            val ground = look?.ground ?: getColor(R.color.bg)
            val ink = look?.ink ?: getColor(R.color.ink)
            val from = (binding.ground.background as? ColorDrawable)?.color ?: ground
            ValueAnimator.ofObject(ArgbEvaluator(), from, ground).apply {
                duration = 220
                addUpdateListener { binding.ground.setBackgroundColor(it.animatedValue as Int) }
            }.start()
            binding.headline.setTextColor(ink)
            binding.subline.setTextColor(ink)
            // What is under the picture takes the picture's colours too.
            worn = look
            described?.let { (h, verdict, holder) -> writeDetails(binding.about, collection, h, verdict, holder, look) }
            val bars = WindowCompat.getInsetsController(window, binding.root)
            bars.isAppearanceLightNavigationBars = Look.isLight(ground)
            // With no answer over the page, the page itself runs up under the status bar.
            if (!binding.band.isVisible) bars.isAppearanceLightStatusBars = Look.isLight(ground)
            // A card's shadow is whichever ink shows on the page.
            for (index in 0 until binding.tiles.childCount) {
                val card = binding.tiles.getChildAt(index).background.mutate() as? LayerDrawable
                card?.findDrawableByLayerId(R.id.shadow)?.setTint(ink)
            }
            if (!celebrate) return@launch
            val paper = look?.paper.orEmpty() + listOf(R.color.lime, R.color.orange, R.color.yellow).map(::getColor)
            binding.tiles.doOnLayout {
                val at = IntArray(2).also(it::getLocationInWindow)
                val page = IntArray(2).also(binding.confetti::getLocationInWindow)
                binding.confetti.burst(at[0] - page[0] + it.width / 2f, at[1] - page[1] + it.height * 0.4f, paper)
            }
        }
    }

    private fun pop(tile: View, index: Int) {
        tile.scaleX = 0.82f
        tile.scaleY = 0.82f
        tile.alpha = 0f
        tile.animate().scaleX(1f).scaleY(1f).alpha(1f).setStartDelay(60L * index).setDuration(320)
            .setInterpolator(OvershootInterpolator(2.2f)).start()
    }

    private fun headline(refusal: Refusal): Int = when (refusal) {
        Refusal.NOT_VALID -> R.string.refused_not_valid
        Refusal.NOT_OWNER -> R.string.refused_not_owner
        Refusal.NOT_ISSUED -> R.string.refused_not_issued
        Refusal.NOT_CHECKED -> R.string.refused_not_checked
    }

    /** Why, in a sentence a doorkeeper can pass on. */
    private fun reason(refusal: Refusal, h: String): String {
        val mine = app.collections.all.firstOrNull { it.collection == collection }?.name.orEmpty()
        return when (refusal) {
            Refusal.NOT_VALID -> getString(R.string.refused_not_valid_why)
            Refusal.NOT_OWNER -> getString(R.string.refused_not_owner_why)
            Refusal.NOT_CHECKED ->
                getString(R.string.refused_not_checked_why, collection.site.substringAfter("://"))
            Refusal.NOT_ISSUED -> {
                // The phone may keep a door for the collection this NFT does belong to.
                val other = app.collections.all.firstOrNull {
                    it.collection != collection && h in Tickets(this, it.collection)
                }
                if (other == null) getString(R.string.refused_not_issued_why, mine)
                else getString(R.string.refused_not_issued_other, other.name, mine)
            }
        }
    }

    private fun returnSoon() {
        returning = lifecycleScope.launch {
            for (left in RETURN_SECONDS downTo 1) {
                binding.next.text = getString(R.string.result_next_in, left)
                delay(1000)
            }
            finish()
        }
    }

    /** Any touch means the doorkeeper is looking: the page stays until they send it away. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN && returning != null) {
            returning?.cancel()
            returning = null
            binding.next.setText(R.string.result_next)
        }
        return super.dispatchTouchEvent(event)
    }

    private fun time(at: Long): String = DateUtils.formatDateTime(
        this, at, DateUtils.FORMAT_SHOW_TIME or if (DateUtils.isToday(at)) 0 else DateUtils.FORMAT_SHOW_DATE
    )

    private fun ago(earlier: Admission): String {
        val now = System.currentTimeMillis()
        if (now - earlier.at * 1000 < DateUtils.MINUTE_IN_MILLIS) return getString(R.string.result_just_now)
        return DateUtils.getRelativeTimeSpanString(earlier.at * 1000, now, DateUtils.MINUTE_IN_MILLIS)
            .toString().replaceFirstChar(Char::lowercase)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_ARRIVAL = "arrival"
        const val EXTRA_TICKET = "ticket"
        const val EXTRA_COLLECTION = "collection"
        private const val RETURN_SECONDS = 4

        /** How far down the page rests when what is known about an NFT is in view: its picture's foot stays above. */
        private const val UNDER = 0.56f

        // What the site calls a collection nobody has named.
        private val UNNAMED = setOf("Collector", "Untitled collection", Guest.UNNAMED)
        private val GREY = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
    }
}
