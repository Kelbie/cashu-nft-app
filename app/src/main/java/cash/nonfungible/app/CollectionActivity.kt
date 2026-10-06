package cash.nonfungible.app

import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import cash.nonfungible.app.databinding.ActivityCollectionBinding
import cash.nonfungible.app.databinding.RowFoundBinding
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.text
import cash.nonfungible.app.entry.whole
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Finds a collection to keep a door for. The site lists its collections and searches them by
 * name, so nobody has to bring an address; a pasted or shared address works as well.
 */
class CollectionActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCollectionBinding
    private val app get() = application as App
    private val site by lazy { Config(this).site }
    /** Looking for NFTs to buy, and not for a collection to keep a door for. */
    private val going by lazy { !intent.getBooleanExtra(EXTRA_DOOR, false) }
    private var looking: Job? = null

    /** A collection the site knows: its page, what it calls itself, how many NFTs it holds. */
    private class Found(val collection: Collection, val name: String, val holds: Int?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A guest who opens a collection's page wants what it has for sale, not a door for it.
        val opened = intent.dataString?.let { Collection.parse(it.substringBefore('#').substringBefore('?')) }
        if (going && opened != null) {
            event(opened, null)
            return finish()
        }
        binding = ActivityCollectionBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.back.setOnClickListener { finish() }
        binding.title.setText(if (going) R.string.guest_find else R.string.home_add_door)
        binding.paste.setOnClickListener {
            val copied = getSystemService<ClipboardManager>()?.primaryClip?.getItemAt(0)
            copied?.coerceToText(this)?.let(binding.search::setText)
        }
        binding.search.doAfterTextChanged { look(it.toString()) }
        // A page shared to the app, or opened with it, is looked up as if it had been pasted.
        val given = if (savedInstanceState == null) shared(intent) else null
        if (given != null) binding.search.setText(given) else look(binding.search.text.toString())
    }

    private fun shared(intent: Intent): String? =
        (intent.dataString ?: intent.getStringExtra(Intent.EXTRA_TEXT))
            ?.let { ADDRESS.find(it)?.value }

    private fun look(typed: String) {
        looking?.cancel()
        val wanted = typed.trim()
        // A link to one NFT on a page names the page.
        val address = Collection.parse(wanted.substringBefore('#').substringBefore('?'))
        val host = site.substringAfter("://")
        looking = lifecycleScope.launch {
            if (address != null) {
                binding.note.text = getString(R.string.collection_reading, address.site.substringAfter("://"))
                show(emptyList())
                val name = app.sync(address)
                if (name == null) {
                    binding.note.setText(R.string.collection_unreachable)
                } else {
                    binding.note.setText(R.string.collection_at_address)
                    show(listOf(Found(address, name, null)))
                }
                return@launch
            }
            // Typing is not asked about letter by letter.
            if (wanted.isNotEmpty()) delay(250)
            if (binding.found.childCount == 0) binding.note.text = getString(R.string.collection_looking, host)
            val found = withContext(Dispatchers.IO) { explore(wanted) }
            binding.note.text = when {
                found == null -> getString(R.string.collection_no_site, host)
                found.isEmpty() -> getString(R.string.collection_none, wanted)
                wanted.isEmpty() -> getString(R.string.collection_popular, host)
                else -> getString(R.string.collection_matches, host)
            }
            show(found.orEmpty())
        }
    }

    private fun show(found: List<Found>) {
        val saved = app.collections.all.map { it.collection }
        binding.found.removeAllViews()
        for (one in found) {
            val row = RowFoundBinding.inflate(layoutInflater, binding.found, true)
            row.cover.clipToOutline = true
            row.name.text = one.name
            row.detail.text = one.holds?.let { resources.getQuantityString(R.plurals.collection_holds, it, it) }
                ?: one.collection.site.substringAfter("://")
            row.added.isVisible = !going && one.collection in saved
            row.root.setOnClickListener { add(one) }
            lifecycleScope.launch {
                row.cover.setImageBitmap(app.pictures.cover(one.collection))
            }
        }
    }

    /** Puts the collection on the door. Its NFTs are read while the door is already up. */
    private fun add(found: Found) {
        if (going) return event(found.collection, found.name)
        // A door believes what its collection's site tells it: who owns an NFT, and which are
        // the collection's. So it is a door only for the site the app is of, and not for a
        // page somewhere else that was pasted or shared to it.
        if (found.collection.site != site) {
            binding.note.text = getString(R.string.collection_other_site, site.substringAfter("://"))
            return
        }
        app.collections.add(found.collection, found.name)
        // Kept, and opened: its page is where its door is.
        startActivity(Intent(this, TicketsActivity::class.java))
        finish()
    }

    private fun event(collection: Collection, name: String?) {
        val event = Intent(this, EventActivity::class.java)
        startActivity(event.putExtra(EventActivity.EXTRA_COLLECTION, collection.url).putExtra(EventActivity.EXTRA_NAME, name))
    }

    /** The site's collections whose name has `wanted` in it, the best liked first. Null if it cannot say. */
    private fun explore(wanted: String): List<Found>? = try {
        val url = "$site/api/explore/collections".toHttpUrl().newBuilder()
            .addQueryParameter("sort", "popular").addQueryParameter("limit", "24")
            .addQueryParameter("q", wanted.take(40)).build()
        HTTP.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val items = (JsonParser.parseString(response.body?.string().orEmpty()) as? JsonObject)
                ?.get("items") as? JsonArray ?: throw IOException("The site listed nothing")
            items.filterIsInstance<JsonObject>().mapNotNull { item ->
                val collection = Collection.parse("$site/p/${item.get("pubkey").text()}")
                val holds = item.get("nfts").whole()?.toInt()
                collection?.let { Found(it, item.get("name").text().orEmpty(), holds) }
            }
        }
    } catch (e: IOException) {
        Log.e(TAG, "Could not search the site: ${e.message}")
        null
    } catch (e: JsonParseException) {
        null
    }

    companion object {
        /** Asked by somebody adding a door to keep: a collection found is kept, not looked through. */
        const val EXTRA_DOOR = "door"
        private const val TAG = "CollectionActivity"
        private val ADDRESS = Regex("https?://[a-z0-9.:-]+/p/[0-9a-f]{64}")
        private val HTTP = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS).build()
    }
}
