package cash.nonfungible.app.entry

import android.content.Context
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.io.IOException

/** When and where a ticket was let in. `at` is in seconds since the epoch. */
@Parcelize
data class Admission(val door: String, val at: Long) : Parcelable

/**
 * The door's record of the tickets of one collection it has let in, by asset hash. A proof
 * shows that the holder owns the ticket now; only this record makes a ticket single use.
 */
class Admitted(private val context: Context, collection: Collection) {
    private val name = "admitted-${collection.key}"
    private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

    val count: Int get() = prefs.all.size

    /** Every admission on record, by asset hash. */
    val all: Map<String, Admission>
        get() = prefs.all.mapNotNull { (h, entry) -> (entry as? String)?.let { h to admission(it) } }
            .toMap()

    private fun admission(entry: String) =
        Admission(entry.substringAfter(' '), entry.substringBefore(' ').toLongOrNull() ?: 0)

    /** Records the ticket as let in, or returns its earlier admission and records nothing. */
    fun record(h: String, door: String): Admission? {
        synchronized(prefs) {
            prefs.getString(h, null)?.let { return admission(it) }
            val entry = "${System.currentTimeMillis() / 1000} $door"
            if (!prefs.edit().putString(h, entry).commit()) {
                // What could not be saved must not linger in memory as if it had been.
                forget(h)
                throw IOException("The admission could not be saved")
            }
            return null
        }
    }

    /** Takes one admission back, so that ticket can come in again. */
    fun forget(h: String) {
        prefs.edit().remove(h).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Forgets the record altogether, when its collection is removed from the phone. */
    fun delete() {
        context.deleteSharedPreferences(name)
    }
}
