package cash.nonfungible.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.util.LruCache
import cash.nonfungible.app.entry.Collection
import cash.nonfungible.app.entry.Metadata
import cash.nonfungible.app.entry.assetHash
import cash.nonfungible.app.entry.text
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** What the phone keeps about an asset besides its picture. */
data class About(
    val metadata: Metadata?,
    val width: Int,
    val height: Int,
    val bytes: Int,
    val format: String,
)

/**
 * What the phone keeps of each NFT: a picture small enough to keep a hundred of, and what the
 * NFT says about itself. A site has only each NFT's whole asset, so one is downloaded once,
 * checked against its hash, and read; nothing is then fetched at the moment of entry.
 */
class Pictures(context: Context) {
    private val memory = object : LruCache<String, Bitmap>(48 shl 20) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val kept = File(context.cacheDir, "nfts").apply { mkdirs() }
    private val downloads = Semaphore(2)

    /** The picture of an asset if the phone has it, without asking anybody. */
    fun held(h: String): Bitmap? = memory[h]
        ?: BitmapFactory.decodeFile(picture(h).path)?.also { memory.put(h, it) }

    /** The picture of an asset, or null if it cannot be had now. */
    suspend fun of(site: String, h: String): Bitmap? =
        memory[h] ?: withContext(Dispatchers.IO) { held(h) ?: downloads.withPermit { fetch(site, h) } }

    /**
     * The picture a site draws of a collection for links to it: a small thing to tell
     * collections apart by in a list, kept only while the app runs.
     */
    suspend fun cover(collection: Collection): Bitmap? {
        val key = "cover:${collection.url}"
        return memory[key] ?: withContext(Dispatchers.IO) {
            try {
                val drawn = Request.Builder().url("${collection.site}/api/og/p/${collection.pubkey}.jpg")
                HTTP.newCall(drawn.build()).execute().use { response ->
                    response.body?.bytes()?.takeIf { response.isSuccessful }
                        ?.let { sampled(it, COVER) }?.also { memory.put(key, it) }
                }
            } catch (e: IOException) {
                null
            }
        }
    }

    /**
     * The picture a collection gave itself on the site, or null when it has none and the site
     * shows its mark instead. Kept while the app runs, misses included.
     */
    suspend fun avatar(collection: Collection): Bitmap? {
        val key = "avatar:${collection.url}"
        if (key in none) return null
        return memory[key] ?: withContext(Dispatchers.IO) {
            try {
                val asked = Request.Builder().url("${collection.site}/api/avatars/${collection.pubkey}.jpg")
                HTTP.newCall(asked.build()).execute().use { response ->
                    if (response.code == 404) none.add(key)
                    response.body?.bytes()?.takeIf { response.isSuccessful }
                        ?.let { sampled(it, AVATAR) }?.also { memory.put(key, it) }
                }
            } catch (e: IOException) {
                null
            }
        }
    }

    /** Collections the site has no picture for. */
    private val none = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** What is known about an asset: null until it has been downloaded once. */
    fun about(h: String): About? = try {
        (JsonParser.parseString(notes(h).readText()) as? JsonObject)?.let { saved ->
            About(
                saved.get("document").text()?.let { Metadata.parse(it.toByteArray(Charsets.US_ASCII)) },
                saved.int("width"), saved.int("height"), saved.int("bytes"),
                saved.get("format").text().orEmpty(),
            )
        }
    } catch (e: IOException) {
        null
    } catch (e: JsonParseException) {
        null
    }

    /**
     * Downloads the assets the phone does not have yet, slowly enough to leave the site's
     * patience for the door. Stops at the first that cannot be had: the rest wait for later.
     */
    suspend fun warm(site: String, hashes: List<String>) = withContext(Dispatchers.IO) {
        for (h in hashes) {
            if (picture(h).exists()) continue
            downloads.withPermit { fetch(site, h) } ?: break
            delay(PAUSE_MS)
        }
    }

    private fun picture(h: String) = File(kept, h)

    private fun notes(h: String) = File(kept, "$h.json")

    private fun fetch(site: String, h: String): Bitmap? = try {
        held(h) ?: HTTP.newCall(Request.Builder().url("$site/api/images/$h").build()).execute().use {
            if (!it.isSuccessful) throw IOException("HTTP ${it.code}")
            // What the phone shows is the asset itself, not whatever the site sent.
            val asset = it.body?.bytes()?.takeIf { sent -> assetHash(sent) == h }
                ?: throw IOException("The picture is not the asset")
            keep(h, asset)
        }
    } catch (e: IOException) {
        Log.e(TAG, "Could not load an NFT's picture: ${e.message}")
        null
    } catch (e: IllegalArgumentException) {
        // Anybody can mint a picture and show it to a door. One the phone cannot draw is not drawn.
        Log.e(TAG, "Could not draw an NFT's picture: ${e.message}")
        null
    }

    private fun keep(h: String, asset: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(asset, 0, asset.size, bounds)
        val small = sampled(asset, SIDE)?.let(::fitted)
            ?: throw IOException("The asset is not a picture")
        val saved = JsonObject().apply {
            addProperty("width", bounds.outWidth)
            addProperty("height", bounds.outHeight)
            addProperty("bytes", asset.size)
            addProperty("format", bounds.outMimeType.orEmpty().substringAfter('/').uppercase())
            // What it says of itself is kept as it wrote it, and read again when asked for.
            Metadata.document(asset)?.let { addProperty("document", it) }
        }
        // A picture with see-through parts keeps them.
        val format = if (small.hasAlpha()) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
        picture(h).outputStream().use { small.compress(format, 88, it) }
        notes(h).writeText(saved.toString())
        memory.put(h, small)
        return small
    }

    private fun fitted(picture: Bitmap): Bitmap {
        val longest = maxOf(picture.width, picture.height)
        if (longest <= SIDE) return picture
        val scale = SIDE.toFloat() / longest
        // A picture a thousand times as long as it is wide still has a side.
        return Bitmap.createScaledBitmap(
            picture, maxOf(1, (picture.width * scale).toInt()), maxOf(1, (picture.height * scale).toInt()), true
        )
    }

    private fun JsonObject.int(name: String): Int =
        (get(name) as? JsonPrimitive)?.takeIf { it.isNumber }?.asInt ?: 0

    companion object {
        private const val TAG = "Pictures"
        private const val SIDE = 720
        private const val COVER = 320
        private const val AVATAR = 160
        private const val PAUSE_MS = 350L
        private val HTTP = OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build()

        /** A picture decoded at the smallest power-of-two reduction that still fills `side`. */
        fun sampled(asset: ByteArray, side: Int): Bitmap? {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(asset, 0, asset.size, options)
            val longest = maxOf(options.outWidth, options.outHeight, 1)
            options.inSampleSize = Integer.highestOneBit(maxOf(longest / side, 1))
            options.inJustDecodeBounds = false
            return BitmapFactory.decodeByteArray(asset, 0, asset.size, options)
        }
    }
}
