package cash.nonfungible.app

import android.content.Context
import cash.nonfungible.app.entry.text
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.security.SecureRandom

/**
 * Somebody on this phone: what they are called, and under what name the phone keeps what is
 * theirs. An account holds NFTs, has a wallet, makes collections and keeps doors. There are no
 * sides to choose between: hosting is something done with a collection, not something one is.
 * `mark` is 64 hex digits of nothing in particular, from which its picture is drawn.
 */
data class Account(val id: String, val name: String, val mark: String = "0".repeat(64)) {
    /**
     * Where the phone keeps one kind of thing for this account. The first account keeps its
     * things where the app kept them before it had accounts, so nothing had to be moved.
     */
    fun store(kind: String): String = if (id == FIRST) kind else "$kind.$id"

    companion object {
        const val FIRST = "first"

        /** A name is what the site will take for one: something, and no more than forty characters. */
        fun named(typed: String): String? = typed.trim().replace(Regex("\\s+"), " ").take(MOST).ifEmpty { null }

        const val MOST = 40
    }
}

/** The accounts on this phone, and the one in use. */
class Accounts(private val context: Context) {
    private val prefs = context.getSharedPreferences("accounts", Context.MODE_PRIVATE)

    val all: List<Account>
        get() = try {
            (JsonParser.parseString(prefs.getString(LIST, null) ?: "[]") as? JsonArray)
                ?.filterIsInstance<JsonObject>().orEmpty().mapNotNull { kept ->
                    val id = kept.get("id").text() ?: return@mapNotNull null
                    Account(id, kept.get("name").text().orEmpty(), kept.get("mark").text()?.takeIf(MARK::matches) ?: "0".repeat(64))
                }
        } catch (e: JsonParseException) {
            emptyList()
        }

    /** The account in use: the one chosen, or the first while none is. None before anybody has named one. */
    val active: Account?
        get() = all.let { kept -> kept.firstOrNull { it.id == prefs.getString(ACTIVE, null) } ?: kept.firstOrNull() }

    /**
     * What a phone that was used before it had accounts called its guest: the name its first
     * account is offered. Everything that phone kept becomes that account's.
     */
    val before: String
        get() = context.getSharedPreferences("guest", Context.MODE_PRIVATE).getString("name", null).orEmpty()

    /** A new account by this name, put in use. The first on a phone takes over what the phone already kept. */
    fun add(name: String): Account {
        val kept = all
        val mark = ByteArray(32).also(RANDOM::nextBytes).joinToString("") { "%02x".format(it) }
        val made = Account(if (kept.none { it.id == Account.FIRST }) Account.FIRST else fresh(kept), name, mark)
        save(kept + made, made.id)
        return made
    }

    fun rename(account: Account, name: String) {
        save(all.map { if (it.id == account.id) it.copy(name = name) else it }, prefs.getString(ACTIVE, null))
    }

    fun use(account: Account) {
        prefs.edit().putString(ACTIVE, account.id).apply()
    }

    /** Forgets an account. What it kept is deleted by whoever asked, before this. */
    fun remove(account: Account) {
        val left = all.filterNot { it.id == account.id }
        save(left, prefs.getString(ACTIVE, null).takeIf { it != account.id } ?: left.firstOrNull()?.id)
    }

    private fun fresh(kept: List<Account>): String {
        while (true) {
            val id = java.lang.Long.toHexString(RANDOM.nextLong()).takeLast(8)
            if (id != Account.FIRST && kept.none { it.id == id }) return id
        }
    }

    private fun save(accounts: List<Account>, active: String?) {
        val list = JsonArray()
        for (one in accounts) {
            list.add(JsonObject().apply {
                addProperty("id", one.id)
                addProperty("name", one.name)
                addProperty("mark", one.mark)
            })
        }
        // Written at once: the next screen reads it back.
        prefs.edit().putString(LIST, list.toString()).putString(ACTIVE, active).commit()
    }

    private companion object {
        const val LIST = "list"
        const val ACTIVE = "active"
        val RANDOM = SecureRandom()
        val MARK = Regex("[0-9a-f]{64}")
    }
}
