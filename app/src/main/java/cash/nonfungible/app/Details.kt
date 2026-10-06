package cash.nonfungible.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.widget.LinearLayout
import androidx.core.graphics.ColorUtils
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import cash.nonfungible.app.databinding.PartDetailsBinding
import cash.nonfungible.app.databinding.RowDetailBinding
import cash.nonfungible.app.databinding.SheetDetailsBinding
import cash.nonfungible.app.databinding.TileFactBinding
import cash.nonfungible.app.entry.Admitted
import cash.nonfungible.app.entry.Attribute
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Holder
import cash.nonfungible.app.entry.Refusal
import cash.nonfungible.app.entry.Ticket
import cash.nonfungible.app.entry.Tickets
import cash.nonfungible.app.entry.Value
import cash.nonfungible.app.entry.Verdict
import com.google.android.material.bottomsheet.BottomSheetDialog

/** What the phone can say about one NFT of a collection without asking anybody. */
class Described(val h: String, val ticket: Ticket?, val about: About?) {
    constructor(app: App, collection: Collection, h: String) :
        this(h, Tickets(app, collection).all.firstOrNull { it.h == h }, app.pictures.about(h))

    private val said = about?.metadata

    /** The name it gives itself, or the one its collection lists it by. */
    val name: String = said?.name ?: ticket?.title?.ifBlank { null }
        ?: "${h.take(8)}…${h.takeLast(4)}"

    /**
     * What sort it is among its collection's: "Crew", "General admission". An NFT says its
     * kind; one that says nothing is listed by its collection as "Crew · 3 of 50".
     */
    val type: String? = said?.kind ?: ticket?.title?.let { TITLED.find(it)?.groupValues?.get(1) }

    /** Its number among its collection's, from what it says of itself or how it is listed. */
    val number: Int? = said?.edition?.number?.takeIf { it <= Int.MAX_VALUE }?.toInt()
        ?: ticket?.title?.let { TITLED.find(it)?.groupValues?.get(2)?.toIntOrNull() }

    /**
     * What tells it from the others of its collection, as it says it: "Crew · 3 of 50". A
     * hundred tickets may share one name, so the name leads only when it says no more.
     */
    val lead: String = listOfNotNull(
        said?.kind,
        said?.edition?.let { edition ->
            edition.of?.let { "${edition.number} of $it" } ?: "#${edition.number}"
        },
    ).joinToString(" · ").ifEmpty { name }

    /** What else it says, on one line: its name and the first of its attributes. */
    val rest: String = (listOf(name).filter { lead != name } + said?.attributes.orEmpty().mapNotNull(::brief).take(2))
        .joinToString(" · ")

    /** An attribute in a word or two. One that holds is its own name, and a date needs a page to be read. */
    private fun brief(attribute: Attribute): String? = when (val value = attribute.value) {
        is Value.Truth -> attribute.trait.takeIf { value.holds }
        else -> attribute.takeIf { it.type != "date" }?.shown("", "")
    }

    companion object {
        private val TITLED = Regex("^(.*) · ([0-9]{1,6}) of [0-9]{1,6}$")

        /** By number, as tickets are counted; then by what they are listed as. */
        val ORDER: Comparator<Described> =
            compareBy<Described>({ it.number ?: Int.MAX_VALUE }, { it.ticket?.title?.lowercase() ?: it.name.lowercase() })
    }
}

/** Everything the phone knows about one NFT, as a sheet: for one of several on a page. */
fun AppCompatActivity.showDetails(collection: Collection, h: String, verdict: Verdict?, holder: Holder?) {
    val sheet = SheetDetailsBinding.inflate(layoutInflater)
    sheet.title.text = Described(application as App, collection, h).name
    writeDetails(sheet.about, collection, h, verdict, holder, null)
    BottomSheetDialog(this).apply { setContentView(sheet.root) }.show()
}

/**
 * Writes everything the phone knows about one NFT. What it says of itself comes first, as
 * tiles: its type, its number, whatever else its maker put in it. Under them is the record:
 * where it is from, when it was minted, what its picture is. All of it is in the colours of
 * `look`, the page's own, taken from the picture; with none, in the app's.
 */
fun AppCompatActivity.writeDetails(
    into: PartDetailsBinding, collection: Collection, h: String, verdict: Verdict?, holder: Holder?, look: Look?,
) {
    val app = application as App
    val nft = Described(app, collection, h)
    val shade = look ?: Look.plain(getColor(R.color.bg), getColor(R.color.ink), getColor(R.color.lime))
    val edge = (2 * resources.displayMetrics.density).toInt()
    val round = 14 * resources.displayMetrics.density
    into.description.text = nft.about?.metadata?.description
    into.description.isVisible = !into.description.text.isNullOrEmpty()
    into.description.setTextColor(shade.ink)
    // The facts, two to a row. The first is what kind of NFT it is, and takes the picture's liveliest colour.
    into.facts.removeAllViews()
    val said = nft.about?.metadata
    // A date is a day, the same one wherever it is read; one too far off to write is shown as its number.
    fun day(at: Long): String? = at.takeIf { it in -DAYS..DAYS }?.let {
        DateUtils.formatDateTime(this, it * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_UTC)
    }
    // Each tile is a label, what it says, and how much of that is read first.
    val tiles = buildList {
        said?.kind?.let { add(Triple(getString(R.string.details_kind), it, 0)) }
        said?.edition?.let { edition ->
            // "11 of 26" is a number and what it is out of: the number is what is read first. A
            // collection added to since has come to more than its pictures say.
            val known = Tickets(this@writeDetails, collection).all.size.toLong()
            val of = edition.of?.let { maxOf(it, known) } ?: known.takeIf { it >= edition.number }
            val number = edition.number.toString()
            add(Triple(getString(R.string.details_number), if (of != null) "$number of $of" else number, number.length))
            edition.name?.let { add(Triple(getString(R.string.details_edition), it, 0)) }
        }
        for (attribute in said?.attributes.orEmpty()) {
            val value = attribute.shown(getString(R.string.details_yes), getString(R.string.details_no), ::day)
            add(Triple(attribute.trait, value, 0))
        }
    }
    tiles.chunked(2).forEachIndexed { line, pair ->
        val across = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        into.facts.addView(across, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = 4 * edge
        })
        pair.forEachIndexed { place, (label, value, read) ->
            val tile = TileFactBinding.inflate(layoutInflater, across, true)
            val first = line == 0 && place == 0
            val fill = if (first) shade.accent else shade.panel
            val on = if (first) Look.inkOn(fill) else shade.ink
            tile.root.background = GradientDrawable().apply {
                setColor(fill)
                setStroke(edge, shade.ink)
                cornerRadius = round
            }
            (tile.root.layoutParams as LinearLayout.LayoutParams).marginStart = if (place == 0) 0 else 4 * edge
            tile.label.text = label
            tile.label.setTextColor(ColorUtils.setAlphaComponent(on, 190))
            tile.value.setTextColor(on)
            tile.value.text = if (read == 0) value else SpannableString(value).apply {
                setSpan(RelativeSizeSpan(0.62f), read, value.length, 0)
            }
        }
    }
    fun time(at: Long) = DateUtils.formatDateTime(
        this, at, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    )
    val admission = Admitted(this, collection).all[h]
    val status = when {
        verdict is Verdict.Refused -> getString(
            when (verdict.reason) {
                Refusal.NOT_VALID -> R.string.refused_not_valid
                Refusal.NOT_OWNER -> R.string.refused_not_owner
                Refusal.NOT_ISSUED -> R.string.refused_not_issued
                Refusal.NOT_CHECKED -> R.string.refused_not_checked
            }
        )
        admission != null -> getString(R.string.details_admitted, admission.door, time(admission.at * 1000))
        else -> getString(R.string.result_waiting)
    }
    // Whether it has come in, and who holds it, is for whoever keeps the collection's door or made it.
    val keeps = app.collections.all.any { it.collection == collection }
    val rows = buildList {
        // Whether it has come in is the door's to know: a guest looking at an NFT is not told.
        if (keeps) add(getString(R.string.details_status) to status)
        holder?.let { add(getString(R.string.details_holder) to "${it.name}\n${it.pubkey.take(12)}…") }
        app.collections.all.firstOrNull { it.collection == collection }
            ?.let { add(getString(R.string.details_collection) to it.name) }
        nft.ticket?.let { ticket ->
            if (ticket.title.isNotBlank() && ticket.title != nft.name) {
                add(getString(R.string.details_listed) to ticket.title)
            }
            if (keeps) {
                add(
                    getString(R.string.details_owner) to
                        getString(if (ticket.sent) R.string.details_passed_on else R.string.details_kept)
                )
            }
            if (ticket.created > 0) add(getString(R.string.details_minted) to time(ticket.created * 1000))
        }
        nft.about?.let {
            val size = Formatter.formatShortFileSize(this@writeDetails, it.bytes.toLong())
            add(getString(R.string.details_picture) to "${it.format} · ${it.width} × ${it.height} · $size")
        }
        add(getString(R.string.details_site) to collection.site.substringAfter("://"))
        add(getString(R.string.details_hash) to h)
    }
    into.rows.removeAllViews()
    fun row(label: String, value: String): RowDetailBinding {
        val row = RowDetailBinding.inflate(layoutInflater, into.rows, true)
        row.rule.isVisible = into.rows.childCount > 1
        row.rule.setBackgroundColor(shade.rule)
        row.label.text = label
        row.label.setTextColor(shade.quiet)
        row.value.text = value
        row.value.setTextColor(shade.ink)
        return row
    }
    for ((label, value) in rows) {
        val row = row(label, value)
        if (value != h) continue
        row.value.setTextAppearance(R.style.Text_App_Mono)
        row.value.setTextColor(shade.ink)
    }
    // What goes with it elsewhere is named, and opened by the phone's browser when asked: never here.
    for (link in said?.links.orEmpty()) {
        val label = link.title ?: link.rel.takeIf { it != "external" }?.replace('-', ' ')?.replaceFirstChar(Char::uppercase)
            ?: getString(R.string.details_link)
        val address = Uri.parse(link.url)
        row(label, address.host?.takeIf { address.scheme == "https" } ?: address.scheme.orEmpty()).root.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, address))
            } catch (e: ActivityNotFoundException) {
                // Nothing on the phone opens it.
            }
        }
    }
}

/** The furthest a date is written as one: the year 9999 either way. */
private const val DAYS = 253402300799L
