package cash.nonfungible.app

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.core.widget.NestedScrollView

/**
 * A page with two places to rest: at its top, where an NFT's picture has the whole of it, and
 * a little way down, where what is known about the NFT sits under the picture. Let go between
 * the two and it goes to one of them, so the details are never an overlay and the picture is
 * one push away. Past the second place it scrolls as any page does.
 */
class Snapping @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    NestedScrollView(context, attrs) {

    /** How far down the second resting place is: nothing while there is nothing under the picture. */
    var rest = 0

    /** Hears which place the page has come to rest at: whether what is under the picture is in view. */
    var onRest: ((under: Boolean) -> Unit)? = null

    private var touching = false
    private val settle = Runnable { if (!touching) settle(0) }

    /** Goes to the other resting place. */
    fun turn() = smoothScrollTo(0, if (scrollY < rest / 2) rest else 0)

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> touching = true
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                touching = false
                lookAgain()
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /** Let go with a push between the two places, it goes the way it was pushed. */
    override fun fling(velocityY: Int) {
        if (scrollY < rest) settle(velocityY) else super.fling(velocityY)
    }

    override fun onScrollChanged(left: Int, top: Int, oldLeft: Int, oldTop: Int) {
        super.onScrollChanged(left, top, oldLeft, oldTop)
        lookAgain()
        if ((top >= rest / 2) != (oldTop >= rest / 2)) onRest?.invoke(rest > 0 && top >= rest / 2)
    }

    /** Once it has stopped moving, it is looked at again. */
    private fun lookAgain() {
        removeCallbacks(settle)
        postDelayed(settle, STILL_MS)
    }

    private fun settle(push: Int) {
        if (rest <= 0 || scrollY <= 0 || scrollY >= rest) return
        val down = if (push != 0) push > 0 else scrollY > rest / 2
        smoothScrollTo(0, if (down) rest else 0)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(settle)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val STILL_MS = 110L
    }
}
