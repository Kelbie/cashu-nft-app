package cash.nonfungible.app.studio

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.security.SecureRandom

/** A page in a hidden WebView that the app asks things of, and that answers by name. */
abstract class Page(context: Context) {
    protected val view = WebView(context)
    private val asked = HashMap<String, CompletableDeferred<String>>()

    // A page tells the app things from another thread. A view that is in no window never runs
    // what is posted to it, and the page that keeps shop is in none.
    private val main = Handler(Looper.getMainLooper())
    private var ready = CompletableDeferred<Unit>()

    /** The view to put in a screen: a page that is in no window may never draw. */
    val window: WebView get() = view

    @SuppressLint("SetJavaScriptEnabled")
    protected fun open(url: String, serve: (Uri) -> WebResourceResponse?) {
        ready = CompletableDeferred()
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.allowFileAccess = false
        view.settings.allowContentAccess = false
        view.addJavascriptInterface(Host(), "host")
        view.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(page: WebView, request: WebResourceRequest) =
                serve(request.url)
        }
        view.loadUrl(url)
    }

    /** Runs `call` in the page with a fresh name for its answer, and waits for that answer. */
    protected suspend fun ask(patienceMs: Long, call: (name: String) -> String): String {
        withTimeout(READY_MS) { ready.await() }
        // Other frames in the page can call the app too. They cannot guess a name.
        val name = java.lang.Long.toHexString(RANDOM.nextLong())
        val answer = CompletableDeferred<String>().also { asked[name] = it }
        try {
            view.evaluateJavascript(call(name), null)
            return withTimeout(patienceMs) { answer.await() }
        } finally {
            asked.remove(name)
        }
    }

    fun close() {
        view.destroy()
    }

    /** What a page may tell the app. A page reaches it from another thread. */
    private inner class Host {
        @JavascriptInterface
        fun ready() = main.post { ready.complete(Unit) }

        @JavascriptInterface
        fun rendered(name: String, made: String) = done(name, made)

        @JavascriptInterface
        fun done(name: String, answer: String) = main.post { asked[name]?.complete(answer) }

        @JavascriptInterface
        fun failed(name: String, why: String) = main.post {
            asked[name]?.completeExceptionally(IOException(why))
        }
    }

    protected companion object {
        const val TAG = "Press"
        const val READY_MS = 30_000L
        val RANDOM = SecureRandom()
        val MIME = mapOf(
            "html" to "text/html", "js" to "text/javascript", "json" to "application/json",
            "png" to "image/png", "jpg" to "image/jpeg", "ttf" to "font/ttf",
        )

        fun served(path: String, body: InputStream?): WebResourceResponse {
            val mime = MIME[path.substringAfterLast('.')] ?: "application/octet-stream"
            return if (body != null) WebResourceResponse(mime, null, body)
            else WebResourceResponse(mime, null, 404, "Not found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
        }
    }
}

/**
 * Draws NFTs with a template. Each template has a page of its own, at an address no network
 * has: the page holds no key and is served nothing but the template and three.js, so a scene
 * written by anybody can draw and can do nothing else.
 */
class Renderer(private val context: Context, private val templates: Templates) : Page(context) {
    private var loaded: String? = null

    /** The JPEG a template draws for a job: `{art, metadata, size}`. */
    suspend fun draw(template: String, job: JsonObject): ByteArray {
        load(template)
        val made = ask(DRAW_MS) { name -> "render(${JsonPrimitive(name)}, $job)" }
        return Base64.decode(made, Base64.DEFAULT)
    }

    /**
     * What a template's scene says its collection is made of, `{ticket, edition, kinds}`, or
     * null when it says nothing. Fails as drawing does when the scene cannot be read.
     */
    suspend fun describe(template: String): JsonObject? {
        load(template)
        val said = ask(DRAW_MS) { name -> "describe(${JsonPrimitive(name)})" }
        return try {
            JsonParser.parseString(said) as? JsonObject
        } catch (e: JsonParseException) {
            null
        }
    }

    private fun load(template: String) {
        if (loaded == template) return
        loaded = template
        open("https://$template.$HOST/render.html") { url -> serve(template, url) }
    }

    private fun serve(template: String, url: Uri): WebResourceResponse {
        val path = url.path.orEmpty().trimStart('/')
        val body = try {
            when {
                url.host != "$template.$HOST" -> null
                path == "render.html" || path == "render.js" || path.startsWith("three/") ->
                    context.assets.open("studio/$path")
                else -> templates.open(template, path)
            }
        } catch (e: IOException) {
            null
        }
        if (body == null) Log.d(TAG, "Nothing for a template at $url")
        return served(path, body)
    }

    private companion object {
        const val HOST = "studio.app.invalid"
        const val DRAW_MS = 90_000L
    }
}

/**
 * Mints on a site with the site's own wallet code, from a page at the site's address: the
 * collection's key is kept where the site keeps keys, and an upload passes the site's check
 * that it comes from a browser. The page is the app's; everything it asks the site for is the
 * site's.
 */
class Minter(private val context: Context, private val site: String) : Page(context) {
    private val pictures = HashMap<String, ByteArray>()

    init {
        open("$site/$OURS/mint.html") { url ->
            val path = url.path.orEmpty().removePrefix("/$OURS/")
            when {
                !url.toString().startsWith("$site/$OURS/") -> null
                path.startsWith("picture/") ->
                    served("picture.jpg", pictures[path.substringAfter('/')]?.let(::ByteArrayInputStream))
                else -> served(path, try { context.assets.open("studio/$path") } catch (e: IOException) { null })
            }
        }
    }

    /** The collections whose keys this phone holds for the site. */
    suspend fun held(): List<String> = call("held").asJsonArray.map { it.asString }

    /** A new collection on the site. Returns its public key. */
    suspend fun create(name: String): String = call("create", name).asString

    /** Mints a picture into a collection and returns the NFT's card: `id`, `h`, `title`. */
    suspend fun add(pubkey: String, picture: ByteArray, title: String): JsonObject {
        val held = java.lang.Long.toHexString(RANDOM.nextLong())
        pictures[held] = picture
        try {
            return call("add", pubkey, held, title).asJsonObject
        } finally {
            pictures.remove(held)
        }
    }

    /** A link that gives an NFT to whoever opens it first. */
    suspend fun link(pubkey: String, card: String): String = call("link", pubkey, card).asString

    /** One round of keeping shop: see `tend` in the page. `wanted` is JSON, `[{id, price}]`. */
    suspend fun tend(pubkey: String, mint: String, wanted: String, accept: String): JsonObject =
        call("tend", pubkey, mint, wanted, accept).asJsonObject

    /** Waits up to `seconds` for news from the market for a collection: whether there was any. */
    suspend fun wait(pubkey: String, seconds: Int): Boolean =
        call("wait", pubkey, seconds.toString()).asBoolean

    /**
     * Offers a listing its asking price out of a collection's money at a mint, if that is no
     * more than `most` sats and the listing is still of the asset `h`.
     */
    suspend fun offer(pubkey: String, mint: String, listing: String, most: Long, h: String) {
        call("offer", pubkey, mint, listing, most.toString(), h)
    }

    /**
     * Takes everything a collection has on sale off sale. The page takes some at a time, at the
     * pace the site allows, and is asked again until it has no more; `taken` hears how many so far.
     */
    suspend fun unlist(pubkey: String, taken: (Int) -> Unit = {}) {
        var count = 0
        repeat(MOST_PAGES) {
            val done = patient("unlist", pubkey).asJsonObject
            count += done.get("taken").asInt
            taken(count)
            if (!done.get("more").asBoolean) return
        }
    }

    /**
     * Every sat a collection has anywhere: spendable, held back, or tied up in a sale at any
     * mint it has used. What deleting it would throw away.
     */
    suspend fun wealth(pubkey: String): Long = call("wealth", pubkey).asLong

    /** What a collection can spend at a mint, in sats. */
    suspend fun balance(pubkey: String, mint: String): Long = call("balance", pubkey, mint).asLong

    /** Ecash to take elsewhere: a token for `amount` sats of a collection's money at a mint. */
    suspend fun token(pubkey: String, mint: String, amount: Long): String =
        call("token", pubkey, mint, amount.toString()).asString

    /** A collection's key, or null if this phone does not hold it. */
    suspend fun key(pubkey: String): String? =
        (call("key", pubkey) as? JsonPrimitive)?.asString

    /** Asks the page to do something by name, and returns what it answers. */
    suspend fun call(action: String, vararg given: String): JsonElement = call(MINT_MS, action, *given)

    /** Asks for something the page does at the site's pace, which may be slow, and waits for it. */
    suspend fun patient(action: String, vararg given: String): JsonElement = call(SLOW_MS, action, *given)

    private suspend fun call(patienceMs: Long, action: String, vararg given: String): JsonElement {
        val arguments = (listOf(action) + given).joinToString(",") { JsonPrimitive(it).toString() }
        return JsonParser.parseString(ask(patienceMs) { name -> "press(${JsonPrimitive(name)},$arguments)" })
    }

    private companion object {
        const val OURS = ".app"
        const val MINT_MS = 120_000L

        /** Long enough for a page of listings or a handful of burns behind a busy address. */
        const val SLOW_MS = 15 * 60_000L

        /** More pages of listings than any collection has: a page that never ends is given up on. */
        const val MOST_PAGES = 500
    }
}
