package cash.nonfungible.app

import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View

/** The colour an answer is given in: one for each thing a doorkeeper does next. */
enum class Tone(val color: Int, val ink: Int) {
    GOOD(R.color.lime, R.color.black),
    TWICE(R.color.yellow, R.color.black),
    BAD(R.color.bad, R.color.white),
    WAIT(R.color.surface, R.color.ink),
}

/** What an answer sounds and feels like, for a doorkeeper who is looking at the guest. */
object Feedback {
    private const val TAG = "Feedback"

    fun give(view: View, tone: Tone, sound: Boolean) {
        val good = tone == Tone.GOOD
        val felt = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> HapticFeedbackConstants.LONG_PRESS
            good -> HapticFeedbackConstants.CONFIRM
            else -> HapticFeedbackConstants.REJECT
        }
        view.performHapticFeedback(felt)
        if (!sound) return
        val heard = when (tone) {
            Tone.GOOD -> ToneGenerator.TONE_PROP_ACK
            Tone.TWICE -> ToneGenerator.TONE_PROP_BEEP2
            else -> ToneGenerator.TONE_PROP_NACK
        }
        try {
            val tones = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 90)
            tones.startTone(heard, 400)
            Handler(Looper.getMainLooper()).postDelayed(tones::release, 700)
        } catch (e: RuntimeException) {
            // A phone with no free audio track stays silent; the screen has said it all.
            Log.w(TAG, "No tone: ${e.message}")
        }
    }
}
