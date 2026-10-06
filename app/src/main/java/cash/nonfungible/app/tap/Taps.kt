package cash.nonfungible.app.tap

import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.IOException

/** What came of holding the phone to a door. */
sealed interface Heard {
    /** The door has the answer. `request` is what it asked. */
    data class Shown(val request: String) : Heard

    /** The door asked and the screen had no answer ready: the request is the screen's to deal with. */
    data class Asked(val request: String) : Heard

    /** The phones parted too soon, or what was in reach was not a door. */
    data class Lost(val why: String) : Heard
}

/**
 * Lets a phone be held to a door while a screen is in front. The phone reads what the door asks
 * and, while the two are still together, hands back what `answer` makes of it. Nothing here
 * needs a network: the door's phone has one, and that is enough. `heard` runs on the main thread.
 */
class Taps(
    private val activity: AppCompatActivity,
    private val heard: (Heard) -> Unit,
    private val answer: suspend (request: String) -> String?,
) : DefaultLifecycleObserver, NfcAdapter.ReaderCallback {
    private val nfc: NfcAdapter? = NfcAdapter.getDefaultAdapter(activity)

    /** Whether this phone can be held to a door, and is set to be. */
    val ready: Boolean get() = nfc?.isEnabled == true

    init {
        activity.lifecycle.addObserver(this)
    }

    override fun onResume(owner: LifecycleOwner) {
        // Read as a card, not as a message: the screen answers in the same tap, in its own time.
        val what = NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS
        nfc?.enableReaderMode(activity, this, what, null)
    }

    override fun onPause(owner: LifecycleOwner) {
        nfc?.disableReaderMode(activity)
    }

    /** Called off the main thread, once for each thing the phone is held to. */
    override fun onTagDiscovered(found: Tag) {
        val card = IsoDep.get(found) ?: return
        val outcome = try {
            card.connect()
            card.timeout = PATIENCE_MS
            val door = Tapped.read(card::transceive)
            // The page that makes an answer runs on the main thread.
            val made = runBlocking { withTimeout(ANSWER_MS) { withContext(Dispatchers.Main) { answer(door.text) } } }
            if (made == null) Heard.Asked(door.text) else Heard.Shown(door.text).also { door.write(made) }
        } catch (e: IOException) {
            Log.d(TAG, "A tap came to nothing: ${e.message}")
            Heard.Lost(e.message.orEmpty())
        } catch (e: TimeoutCancellationException) {
            Heard.Lost("")
        } finally {
            try {
                card.close()
            } catch (e: IOException) {
                Log.d(TAG, "The door was already gone")
            }
        }
        activity.runOnUiThread { heard(outcome) }
    }

    private companion object {
        const val TAG = "Taps"
        const val PATIENCE_MS = 4000
        const val ANSWER_MS = 8000L
    }
}
