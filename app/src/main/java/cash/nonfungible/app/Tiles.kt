package cash.nonfungible.app

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import kotlin.math.ceil
import kotlin.math.min

/** Lays its children out as squares, in the grid that shows them largest. */
class Tiles @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    ViewGroup(context, attrs) {

    private val gap = (10 * resources.displayMetrics.density).toInt()

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), MeasureSpec.getSize(heightSpec))
        val side = MeasureSpec.makeMeasureSpec(side(columns()), MeasureSpec.EXACTLY)
        for (index in 0 until childCount) getChildAt(index).measure(side, side)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        if (childCount == 0) return
        val columns = columns()
        val side = side(columns)
        val rows = rows(columns)
        val above = (measuredHeight - rows * side - (rows - 1) * gap) / 2
        for (index in 0 until childCount) {
            val row = index / columns
            // A last row that is not full sits in the middle.
            val inRow = if (row == rows - 1) childCount - row * columns else columns
            val before = (measuredWidth - inRow * side - (inRow - 1) * gap) / 2
            val x = before + (index % columns) * (side + gap)
            val y = above + row * (side + gap)
            getChildAt(index).layout(x, y, x + side, y + side)
        }
    }

    private fun rows(columns: Int): Int = ceil(childCount / columns.toFloat()).toInt().coerceAtLeast(1)

    private fun side(columns: Int): Int {
        val rows = rows(columns)
        return min(
            (measuredWidth - (columns - 1) * gap) / columns,
            (measuredHeight - (rows - 1) * gap) / rows,
        ).coerceAtLeast(0)
    }

    private fun columns(): Int = (1..childCount.coerceAtLeast(1)).maxBy(::side)
}
