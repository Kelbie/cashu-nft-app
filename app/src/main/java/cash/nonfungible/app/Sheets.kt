package cash.nonfungible.app

import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import cash.nonfungible.app.databinding.SheetConfirmBinding
import cash.nonfungible.app.entry.Collection
import kotlinx.coroutines.launch
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog

/**
 * A sheet with `content` in it, opened whole. Every sheet in the app is made here, so each
 * comes up the same way: none of them is long enough to want to be half open.
 */
fun AppCompatActivity.sheetOf(content: View): BottomSheetDialog = BottomSheetDialog(this).apply {
    setContentView(content)
    behavior.skipCollapsed = true
    behavior.state = BottomSheetBehavior.STATE_EXPANDED
}

/**
 * Asks before something is done, on a sheet like any other: what it is, what it means, and two
 * buttons. What cannot be undone is `forGood`: its button is the red one that must be held.
 */
fun AppCompatActivity.confirm(title: String, message: String, yes: String, forGood: Boolean = false, act: () -> Unit) {
    val sheet = SheetConfirmBinding.inflate(layoutInflater)
    val dialog = sheetOf(sheet.root)
    sheet.title.text = title
    sheet.text.text = message
    sheet.no.setOnClickListener { dialog.dismiss() }
    sheet.yes.isVisible = !forGood
    sheet.hold.isVisible = forGood
    sheet.yes.text = yes
    sheet.hold.text = yes
    val agreed = {
        dialog.dismiss()
        act()
    }
    sheet.yes.setOnClickListener { agreed() }
    sheet.hold.onHeld = agreed
    dialog.show()
}

/**
 * A few of a collection's pictures, small and square, in `into`: what the eye knows it by
 * before it reads a name or a number.
 */
fun AppCompatActivity.thumbs(into: ViewGroup, collection: Collection, hashes: List<String>, dp: Int = 22) {
    val app = application as App
    val side = (dp * resources.displayMetrics.density).toInt()
    for (h in hashes) {
        val thumb = ImageView(this)
        thumb.setBackgroundResource(R.drawable.bg_thumb)
        thumb.clipToOutline = true
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        thumb.setPadding(side / 13, side / 13, side / 13, side / 13)
        into.addView(thumb, LinearLayout.LayoutParams(side, side).apply { marginEnd = side / 5 })
        app.pictures.held(h)?.let { thumb.setImageBitmap(it); return@let } ?: lifecycleScope.launch {
            app.pictures.of(collection.site, h)?.let(thumb::setImageBitmap)
        }
    }
}
