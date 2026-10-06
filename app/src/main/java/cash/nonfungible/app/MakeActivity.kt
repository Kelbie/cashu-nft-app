package cash.nonfungible.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.Base64
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import cash.nonfungible.app.databinding.ActivityMakeBinding
import cash.nonfungible.app.databinding.DialogOwnBinding
import cash.nonfungible.app.databinding.RowKindBinding
import cash.nonfungible.app.databinding.TilePreviewBinding
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Tickets
import cash.nonfungible.app.entry.text
import cash.nonfungible.app.studio.Kind
import cash.nonfungible.app.studio.Made
import cash.nonfungible.app.studio.Minter
import cash.nonfungible.app.studio.Renderer
import cash.nonfungible.app.studio.Template
import cash.nonfungible.app.studio.Templates
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Makes a collection, or issues more of one this phone made: a name, a template that draws
 * each NFT, and how many of each kind. Every NFT is drawn here and minted on the site with the
 * site's own wallet, under a key that stays on this phone.
 */
class MakeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMakeBinding
    private val app get() = application as App
    private val templates by lazy { Templates(this) }
    private val renderer by lazy { Renderer(this, templates).also { binding.pages.addView(it.window) } }
    private var minter: Minter? = null

    /** The collection issued into: none yet for one that is still to be made. */
    private var collection: Collection? = null
    private val site by lazy { collection?.site ?: Config(this).site }
    private lateinit var template: Template

    /** A kind on the form: its name, what the template is told about it, how many to make. */
    private class Row(var label: String, val options: JsonObject, var count: Int) {
        /** What the kind is called where it has to be called something. */
        val kind get() = label.ifEmpty { "NFT" }
    }

    private val rows = ArrayList<Row>()

    /** The type in view among the previews: the one last touched. */
    private var shown = 0
    private var drawing: Job? = null

    /** The types whose pictures are still to be drawn. */
    private var waiting = emptyList<Int>()
    private var making: Job? = null
    private var stopping = false

    /** What this sitting has made, in the order of their places on the grid: null where the site made one and its answer was lost. */
    private val madeHere = ArrayList<JsonObject?>()

    /** The NFT whose minting was under way when the last attempt stopped: its kind and number. */
    private var unsure: Pair<Row, Int>? = null

    /** The guest's portrait, as a template takes one: a data URL. */
    private var portrait: String? = null
    private val pickPhoto = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked ->
        val chosen = picked?.let { contentResolver.openInputStream(it)?.use { photo -> photo.readBytes() } }
        // A template needs a face, not a photograph's every pixel.
        val small = chosen?.let { Pictures.sampled(it, PORTRAIT) } ?: return@registerForActivityResult
        val png = ByteArrayOutputStream().also { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        portrait = "data:image/png;base64," + Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP)
        binding.photo.setText(R.string.make_photo_chosen)
        preview(listOf(shown))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMakeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { onBackPressedDispatcher.onBackPressed() }
        collection = intent.getStringExtra(EXTRA_COLLECTION)?.let(Collection::parse)
        val issuing = collection
        if (issuing == null) {
            binding.title.setText(R.string.make_title)
            choose(templates.all.first())
            binding.name.doAfterTextChanged { update() }
        } else {
            // More of a collection this phone made: its look and its kinds are settled.
            val made = Made(this, issuing)
            template = made.template?.let(templates::named) ?: return finish()
            rows += made.kinds.mapIndexed { index, kind -> Row(kind.label, kind.options, if (index == 0) 1 else 0) }
            binding.title.text = app.collections.all.firstOrNull { it.collection == issuing }?.name
            binding.name.isVisible = false
            binding.looks.isVisible = false
            showKinds()
        }
        binding.addKind.setOnClickListener {
            // The next kind the template suggests, or one of the first's look to name.
            val next = template.kinds.getOrNull(rows.size)
            rows += Row(next?.label.orEmpty(), next?.options ?: rows.first().options, 1)
            showKinds()
        }
        binding.guest.doAfterTextChanged { preview(listOf(shown)) }
        binding.photo.setOnClickListener {
            pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        }
        binding.make.setOnClickListener { if (making?.isActive == true) stopping = true else make() }
        // Leaving half way would lose count of what was minted: the one under way is finished.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (making?.isActive == true) stopping = true else finish()
            }
        })
    }

    override fun onDestroy() {
        renderer.close()
        minter?.close()
        super.onDestroy()
    }

    /**
     * Takes a template's look and the kinds it suggests. A template that says how many of each to
     * make is taken at its word; for any other the numbers already asked for are kept.
     */
    private fun choose(chosen: Template) {
        template = chosen
        val counts = rows.map { it.count }
        rows.clear()
        rows += chosen.kinds.mapIndexed { index, kind ->
            Row(kind.label, kind.options, kind.count ?: counts.getOrElse(index) { if (index == 0) FIRST else 0 })
        }
        shown = 0
        binding.templates.removeAllViews()
        for (one in templates.all) pill(one.name, one == chosen) { choose(one) }
        pill(getString(R.string.make_own), false) { bringOwn() }
        showKinds()
    }

    private fun pill(label: String, chosen: Boolean, act: () -> Unit) {
        val pill = layoutInflater.inflate(R.layout.pill, binding.templates, false) as TextView
        pill.text = label
        pill.isSelected = chosen
        pill.setOnClickListener { act() }
        binding.templates.addView(pill)
    }

    /** A line for each type, and a picture of each side by side above them. */
    private fun showKinds() {
        binding.kinds.removeAllViews()
        binding.previews.removeAllViews()
        // Only tickets come in ticket types.
        binding.addKind.setText(if (template.ticket) R.string.make_add_kind else R.string.make_add_kind_any)
        rows.forEachIndexed { index, row ->
            TilePreviewBinding.inflate(layoutInflater, binding.previews, true).picture.clipToOutline = true
            val line = RowKindBinding.inflate(layoutInflater, binding.kinds, true)
            line.label.setHint(if (template.ticket) R.string.make_kind else R.string.make_kind_any)
            line.label.setText(row.label)
            line.count.setText(row.count.toString())
            line.label.doAfterTextChanged {
                row.label = it.toString().trim()
                look(index)
                preview(listOf(index))
            }
            line.count.doAfterTextChanged {
                row.count = (it.toString().toIntOrNull() ?: 0).coerceIn(0, Templates.MOST)
                look(index)
                update()
                // Each picture says how many there are in all: the one in view first, then the rest.
                preview(listOf(index) + (rows.indices - index))
            }
            line.fewer.setOnClickListener { line.count.setText((row.count - 1).coerceAtLeast(0).toString()) }
            line.more.setOnClickListener { line.count.setText((row.count + 1).coerceAtMost(Templates.MOST).toString()) }
        }
        shown = shown.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
        update()
        preview(rows.indices.toList())
    }

    /** Brings the picture of the type being changed into view. */
    private fun look(index: Int) {
        shown = index
        val tile = binding.previews.getChildAt(index) ?: return
        binding.previewsScroll.post {
            binding.previewsScroll.smoothScrollTo(tile.left - (binding.previewsScroll.width - tile.width) / 2, 0)
        }
    }

    /** What the button will do. */
    private fun update() {
        val batch = rows.sumOf { it.count }
        binding.make.isEnabled = batch > 0
        binding.make.text = if (batch == 0) getString(R.string.make_none)
        else resources.getQuantityString(R.plurals.make_button, batch, batch)
        // Whose it is shows on the picture of the one NFT being made.
        binding.whose.isVisible = batch == 1
        val issued = collection?.let { Made(this, it).issued }
        binding.status.text = if (issued == null) getString(R.string.make_help, site.substringAfter("://"))
        else getString(R.string.make_help_issue, issued, issued + 1)
    }

    /** Draws how one NFT of each of these types will look: dots until its picture is drawn. */
    private fun preview(which: List<Int>) {
        if (making?.isActive == true) return
        // What was waiting to be drawn is drawn as well: nothing is left as dots.
        val wanted = (which + waiting).distinct()
        waiting = wanted
        drawing?.cancel()
        for (index in wanted) binding.previews.getChildAt(index)?.let { TilePreviewBinding.bind(it).dots.isVisible = true }
        drawing = lifecycleScope.launch {
            // Typing is not drawn letter by letter.
            delay(350)
            val made = collection?.let { Made(this@MakeActivity, it) }
            val issued = made?.issued ?: 0
            val total = made?.total?.takeIf { it > 0 } ?: (issued + rows.sumOf { it.count }.coerceAtLeast(1))
            for (index in wanted) {
                val row = rows.getOrNull(index) ?: continue
                val tile = binding.previews.getChildAt(index)?.let(TilePreviewBinding::bind) ?: continue
                // The first of its kind that would be made: numbered after the kinds before it.
                val serial = issued + rows.take(index).sumOf { it.count } + 1
                val place = (made?.issuedOf(row.kind) ?: 0) + 1
                try {
                    val drawn = renderer.draw(template.id, job(serial, total, place, row, guest().takeIf { row.count > 0 }, PREVIEW))
                    tile.picture.setImageBitmap(Pictures.sampled(drawn, PREVIEW))
                    tile.dots.isVisible = false
                    waiting = waiting - index
                } catch (e: IOException) {
                    binding.status.text = getString(R.string.make_preview_failed, e.message)
                } catch (e: TimeoutCancellationException) {
                    binding.status.text = getString(R.string.make_preview_failed, getString(R.string.guest_slow))
                }
            }
        }
    }

    private fun name(): String = collection?.let { made ->
        app.collections.all.firstOrNull { it.collection == made }?.name
    } ?: binding.name.text.toString().trim()

    /** Whose the one NFT being made is: a name, a face, or both. Nobody's when several are made. */
    private fun guest(): JsonObject? {
        val name = binding.guest.text.toString().trim()
        if (rows.sumOf(Row::count) != 1 || (name.isEmpty() && portrait == null)) return null
        return JsonObject().apply {
            addProperty("id", "guest")
            addProperty("name", name)
            portrait?.let { addProperty("image", it) }
        }
    }

    /**
     * What a template is given to draw one NFT, and what the picture will say about itself unless
     * the template says more. `serial` is its number in the collection and `place` its number
     * among its own kind.
     */
    private fun job(serial: Int, total: Int, place: Int, row: Row, guest: JsonObject?, size: Int): JsonObject {
        val name = name().ifEmpty { getString(R.string.make_sample) }
        val label = row.kind
        val digest = MessageDigest.getInstance("SHA-256").digest("${collection?.pubkey ?: name}:$serial".toByteArray())
        // The seed is this NFT's for good; the entropy is this drawing's and never comes again.
        val fresh = ByteArray(32).also(RANDOM::nextBytes)
        fun fact(trait: String, value: String) = JsonObject().apply {
            addProperty("trait_type", trait)
            addProperty("value", value)
        }
        val art = JsonObject().apply {
            addProperty("serial", serial)
            addProperty("total", total)
            addProperty("index", place)
            addProperty("seed", digest.joinToString("") { "%02x".format(it) })
            addProperty("entropy", fresh.joinToString("") { "%02x".format(it) })
            add("collection", JsonObject().apply {
                addProperty("name", name)
                addProperty("edition", template.edition)
            })
            add("category", JsonObject().apply {
                addProperty("id", label.lowercase().replace(Regex("[^a-z0-9]+"), "-"))
                addProperty("label", label)
            })
            add("person", guest ?: JsonNull.INSTANCE)
            add("options", row.options)
        }
        val metadata = JsonObject().apply {
            addProperty("name", name)
            addProperty("kind", label.take(40))
            add("edition", JsonObject().apply {
                addProperty("number", serial)
                addProperty("of", total)
                if (template.edition.isNotBlank()) addProperty("name", template.edition.take(40))
            })
            add("attributes", JsonArray().apply {
                guest?.get("name")?.asString?.takeIf { it.isNotEmpty() }?.let { add(fact("Guest", it)) }
            })
            collection?.let { made ->
                add("collection", JsonObject().apply {
                    addProperty("id", made.pubkey)
                    addProperty("url", made.url)
                })
                // A ticket says its collection's door lets it in, so a wallet can tell a ticket
                // from a picture and knows which one a door is asking for with nobody to ask.
                if (template.ticket) add("ext", JsonObject().apply { add("ticket", JsonObject()) })
            }
        }
        return JsonObject().apply {
            add("art", art)
            add("metadata", metadata)
            addProperty("size", size)
        }
    }

    /** Draws and mints what the form asks for, one NFT at a time, each shown as it is made. */
    private fun make() {
        val name = name()
        if (name.isEmpty()) return binding.status.setText(R.string.make_unnamed)
        val batch = rows.sumOf { it.count }
        val guest = guest()
        val host = site.substringAfter("://")
        drawing?.cancel()
        stopping = false
        binding.form.isVisible = false
        // The grid is decided once, for everything this sitting set out to make. Carrying on
        // after a failure fills the places that are left.
        if (!binding.made.isVisible) {
            binding.made.begin(batch)
            binding.made.onPick = { place -> val card = madeHere.getOrNull(place)
                val id = card?.get("id").text()
                val h = card?.get("h").text()
                collection?.let { into -> if (id != null && h != null) handOver(into, id, h, card?.get("title").text().orEmpty()) }
            }
        }
        binding.made.isVisible = true
        binding.make.setText(R.string.make_stop)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        making = lifecycleScope.launch {
            var done = 0
            try {
                val press = minter ?: Minter(this@MakeActivity, site).also {
                    minter = it
                    binding.pages.addView(it.window)
                }
                val into = collection ?: run {
                    binding.status.text = getString(R.string.make_creating, host)
                    val made = Collection.parse("$site/p/${press.create(name)}")
                        ?: throw IOException("The site made no collection")
                    app.collections.add(made, name, mine = true)
                    collection = made
                    made
                }
                val record = Made(this@MakeActivity, into)
                // The types of a collection are the ones it has NFTs of: a suggestion nobody took up is not one.
                val asked = rows.filter { it.count > 0 }.map { Kind(it.kind, it.options) }
                record.save(template, (record.kinds + asked).distinctBy(Kind::label))
                // An NFT can be minted and its answer lost. The site's page is what was made:
                // numbering carries on from the highest number it lists.
                if (record.issued > 0 || unsure != null) {
                    app.sync(into)
                    val numbered = Tickets(this@MakeActivity, into).all
                        .mapNotNull { NUMBER.find(it.title)?.groupValues }
                        .mapNotNull { found -> found[1].toIntOrNull()?.let { it to found[2].toInt() } }
                    val listed = numbered.map { it.first }
                    unsure?.let { (row, serial) ->
                        if (serial in listed && row.count > 0) {
                            record.count(row.kind)
                            row.count--
                            done++
                            // It was made after all; its place is taken, though its picture is not at hand.
                            madeHere += null
                            binding.made.made(null)
                        }
                    }
                    record.issued = maxOf(record.issued, listed.maxOrNull() ?: 0)
                    // A collection numbered before the app kept its count is "of" what its first NFT says.
                    if (record.total == 0) record.total = numbered.minByOrNull { it.first }?.second ?: 0
                }
                unsure = null
                // The first run fixes what every NFT is "of"; later ones count past it.
                val total = record.total.takeIf { it > 0 } ?: (record.issued + rows.sumOf { it.count }).also { record.total = it }
                for (row in rows) {
                    while (row.count > 0 && !stopping) {
                        val started = SystemClock.elapsedRealtime()
                        val serial = record.issued + 1
                        binding.status.text = getString(R.string.make_drawing, done + 1, batch)
                        val picture = renderer.draw(template.id, job(serial, total, record.issuedOf(row.kind) + 1, row, guest, SIZE))
                        binding.status.text = getString(R.string.make_minting, done + 1, batch, host)
                        // It is listed by its kind, whatever its template calls it: prices are by kind.
                        val title = "${row.kind.take(50)} · $serial of $total"
                        unsure = row to serial
                        val card = press.add(into.pubkey, picture, title)
                        unsure = null
                        // The number is spent the moment the site has the NFT.
                        record.issued = serial
                        record.count(row.kind)
                        row.count--
                        done++
                        madeHere += card
                        // A few are shown large and sharp; many are shown small, and kept small.
                        binding.made.made(Pictures.sampled(picture, if (batch <= 9) 512 else 128))
                        // The site lets one address ask only so much in a minute.
                        delay(PACE_MS - (SystemClock.elapsedRealtime() - started))
                    }
                }
                app.sync(into)
                binding.status.text = resources.getQuantityString(R.plurals.make_done, done, done)
                binding.confetti.burst(binding.root.width / 2f, binding.root.height * 0.45f, listOf(R.color.lime, R.color.orange, R.color.yellow).map(::getColor))
                Feedback.give(binding.root, Tone.GOOD, Config(this@MakeActivity).sound)
                binding.make.setText(R.string.make_finish)
                binding.make.setOnClickListener {
                    // A collection just made is gone to: its page is where it is priced, sold from and let in.
                    if (intent.getStringExtra(EXTRA_COLLECTION) == null) {
                        app.collections.select(into)
                        startActivity(Intent(this@MakeActivity, TicketsActivity::class.java))
                    }
                    finish()
                }
            } catch (e: IOException) {
                failed(done, e.message)
            } catch (e: TimeoutCancellationException) {
                failed(done, e.message)
            }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** What is left to make stays asked for, so the same button carries on. */
    private fun failed(done: Int, why: String?) {
        binding.status.text = getString(R.string.make_failed, done, why.orEmpty())
        binding.make.setText(R.string.make_again)
    }

    /** A template of the holder's own: a script somebody wrote to the brief, pasted in. */
    private fun bringOwn() {
        val own = DialogOwnBinding.inflate(layoutInflater)
        own.brief.setOnClickListener {
            val brief = assets.open("studio/BRIEF.md").reader().use { it.readText() }
            getSystemService<ClipboardManager>()?.setPrimaryClip(ClipData.newPlainText("brief", brief))
            Toast.makeText(this, R.string.own_copied, Toast.LENGTH_SHORT).show()
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.own_title)
            .setView(own.root)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.own_save) { _, _ ->
                val scene = own.scene.text.toString()
                if (scene.isNotBlank()) {
                    describe(templates.add(own.name.text.toString().trim().ifEmpty { getString(R.string.own_title) }, scene))
                }
            }
            .show()
    }

    /**
     * Asks a scene the holder brought what its collection is made of, and offers that: its
     * kinds and how many of each. One that says nothing, or cannot be read, is offered as
     * tickets of one kind, and its preview says what is wrong with it.
     */
    private fun describe(brought: Template) {
        lifecycleScope.launch {
            val said = try {
                renderer.describe(brought.id)
            } catch (e: IOException) {
                null
            } catch (e: TimeoutCancellationException) {
                null
            }
            choose(said?.let { templates.describe(brought, it) } ?: brought)
        }
    }

    companion object {
        const val EXTRA_COLLECTION = "collection"

        /** How many of the first kind a new collection starts with. */
        private const val FIRST = 10
        private const val PREVIEW = 640
        private val RANDOM = SecureRandom()

        // How an NFT made here is listed: its kind, then "12 of 50".
        private val NUMBER = Regex("· ([0-9]{1,6}) of ([0-9]{1,6})$")
        private const val PORTRAIT = 512
        private const val SIZE = 1440

        // An NFT takes about nine requests to mint, and a site allows 240 a minute.
        private const val PACE_MS = 2600L
    }
}
