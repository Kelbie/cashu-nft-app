package cash.nonfungible.app

import android.content.Context
import cash.nonfungible.app.entry.Collection
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** What is set for this phone, whoever's account is in use: how its doors behave, where the app was left, a debug build's site. */
class Config(context: Context) {
    private val prefs = context.getSharedPreferences("config", Context.MODE_PRIVATE)
    private val defaultDoor = context.getString(R.string.settings_door_default)

    val door: String get() = prefs.getString(DOOR, null) ?: defaultDoor

    /** Which of an account's three things the app was last showing. */
    var tab: String?
        get() = prefs.getString(TAB, null)
        set(shown) = prefs.edit().putString(TAB, shown).apply()

    /**
     * What opened the app before anybody had said what to call them: a link, or what a door
     * said. It is done once they have.
     */
    var pending: String?
        get() = prefs.getString(PENDING, null)
        set(came) = prefs.edit().putString(PENDING, came).commit().let { }

    /** The collection whose door this phone is, while it is one: it is a door again when the app is next opened. */
    var doorOpen: String?
        get() = prefs.getString(DOOR_OPEN, null)
        set(collection) = prefs.edit().putString(DOOR_OPEN, collection).apply()

    /** The site whose collections the phone searches. A debug build may be pointed at another. */
    val site: String
        get() = debugSite.takeIf { BuildConfig.DEBUG && Collection.parse("$it/p/${"0".repeat(64)}") != null }
            ?: SITE

    /** Whether an address is a page of the site, and not merely one that begins like it. */
    fun onSite(address: String): Boolean = address.toHttpUrlOrNull()?.let { page ->
        val usual = page.port == okhttp3.HttpUrl.defaultPort(page.scheme)
        "${page.scheme}://${page.host}" + (if (usual) "" else ":${page.port}") == site &&
            page.username.isEmpty() && page.password.isEmpty()
    } ?: false

    /** The site a debug build was told to search instead, as typed. */
    val debugSite: String get() = prefs.getString(DEBUG_SITE, null).orEmpty()

    /** Whether an answer is also heard. */
    val sound: Boolean get() = prefs.getBoolean(SOUND, true)

    /** Whether a door that a phone can be held to shows its code instead. */
    var code: Boolean
        get() = prefs.getBoolean(CODE, false)
        set(shown) = prefs.edit().putBoolean(CODE, shown).apply()

    /** Whether the code comes back by itself after a plain admission. */
    val express: Boolean get() = prefs.getBoolean(EXPRESS, true)

    /** The relays a debug build was told to use instead of the public ones, as typed. */
    val debugRelays: String get() = prefs.getString(RELAYS, null).orEmpty()

    /** Where answers arrive: never none. Every relay lengthens the request, so there are few. */
    val relays: List<String>
        get() = debugRelays.split(Regex("[\\s,]+")).filter(::isRelay)
            .takeIf { BuildConfig.DEBUG && it.isNotEmpty() } ?: PUBLIC_RELAYS

    private fun isRelay(url: String): Boolean = Regex("^wss?://").containsMatchIn(url) &&
        url.replaceFirst("ws", "http").toHttpUrlOrNull() != null

    fun save(
        door: String,
        debugRelays: String,
        debugSite: String = this.debugSite,
        sound: Boolean = this.sound,
        express: Boolean = this.express,
    ) {
        prefs.edit()
            .putString(DOOR, door.trim().ifEmpty { defaultDoor })
            .putString(RELAYS, debugRelays.trim())
            .putString(DEBUG_SITE, debugSite.trim().trimEnd('/'))
            .putBoolean(SOUND, sound)
            .putBoolean(EXPRESS, express)
            .apply()
    }

    /**
     * Keeps a debug build pointed at the site and relays it was testing on, and nothing else:
     * written at once, because the app is about to stop. Without this a debug build that had
     * just deleted everything would wake up pointed at the real site.
     */
    fun keepTesting(site: String, relays: String) {
        prefs.edit().clear().putString(DEBUG_SITE, site).putString(RELAYS, relays).commit()
    }

    companion object {
        private const val DOOR = "door"
        private const val RELAYS = "relays"
        private const val DEBUG_SITE = "site"
        private const val SITE = "https://nonfungible.cash"
        private const val SOUND = "sound"
        private const val EXPRESS = "express"
        private const val CODE = "code"
        private const val TAB = "tab"
        private const val PENDING = "pending"
        private const val DOOR_OPEN = "door_open"
        // Both serve gift wraps to an unauthenticated listener; a relay that wants NIP-42
        // authentication first, as relay.damus.io does, would never deliver an answer here.
        private val PUBLIC_RELAYS = listOf("wss://relay.primal.net", "wss://nos.lol")
    }
}
