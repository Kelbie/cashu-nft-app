package cash.nonfungible.app

import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import cash.nonfungible.app.databinding.SheetDeleteBinding
import cash.nonfungible.app.entry.Tickets
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Deletes a collection, on a sheet that says what that means and asks for its button to be held.
 *
 * One this phone minted is deleted as far as it can be: what it has for sale comes off sale, and
 * every NFT it still holds is burned at the mint and dropped from the site with its picture.
 * What it has sent or sold belongs to somebody else and stays theirs, and the site keeps the
 * collection's name, with nothing under it. One that was only added is taken off this phone,
 * and nothing changes on the site. `after` runs once it is gone from the phone.
 */
fun AppCompatActivity.deleteCollection(saved: Saved, after: () -> Unit) {
    val app = application as App
    val collection = saved.collection
    val owned = app.owns(collection)
    val sheet = SheetDeleteBinding.inflate(layoutInflater)
    val dialog = sheetOf(sheet.root)
    val listed = Tickets(this, collection).all
    val held = listed.count { !it.sent }
    sheet.title.text = getString(if (owned) R.string.delete_title else R.string.delete_remove_title, saved.name)
    sheet.text.text = if (!owned) getString(R.string.delete_remove_help)
    else getString(R.string.delete_help, held, listed.size, collection.site.substringAfter("://"), listed.size - held)
    sheet.hold.text = getString(if (owned) R.string.delete_hold else R.string.delete_remove_hold)
    sheet.close.setOnClickListener { dialog.dismiss() }
    var gone = false
    dialog.setOnDismissListener { if (gone) after() }

    fun finished(said: String) {
        gone = true
        sheet.dots.isVisible = false
        sheet.tick.play()
        sheet.note.text = said
        sheet.close.isEnabled = true
        sheet.close.setText(R.string.guest_done)
        Feedback.give(sheet.root, Tone.GOOD, Config(this).sound)
    }

    /** The button back as it was, to be held again. */
    fun again() {
        sheet.stage.isVisible = false
        sheet.hold.reset()
        sheet.hold.isVisible = true
    }

    fun begin() {
        sheet.hold.isVisible = false
        sheet.note.isVisible = true
        if (!owned) {
            // Nothing to wait for: it is only this phone that forgets it.
            app.forget(app.account, collection)
            sheet.stage.isVisible = true
            sheet.dots.isVisible = false
            return finished(getString(R.string.delete_removed))
        }
        sheet.stage.isVisible = true
        sheet.close.isEnabled = false
        dialog.setCancelable(false)
        sheet.note.setText(R.string.delete_unlisting)
        lifecycleScope.launch {
            val done = try {
                app.sales.destroy(
                    collection,
                    { taken -> if (taken > 0) sheet.note.text = getString(R.string.delete_unlisted, taken) },
                    { burned, of -> sheet.note.text = getString(R.string.delete_burning, burned, of) },
                )
            } catch (e: IOException) {
                e.message
            } catch (e: TimeoutCancellationException) {
                getString(R.string.guest_slow)
            }
            dialog.setCancelable(true)
            sheet.close.isEnabled = true
            when (done) {
                is cash.nonfungible.app.studio.Destroyed -> if (done.left == 0) {
                    // Nothing of it is left to sell or to keep a door for.
                    app.forget(app.account, collection)
                    finished(resources.getQuantityString(R.plurals.delete_done, done.burned, done.burned))
                } else {
                    // What could not be burned is still the collection's: it stays on the phone, to be tried again.
                    sheet.note.text = getString(R.string.delete_partly, done.burned, done.left, done.problem.orEmpty())
                    again()
                }
                else -> {
                    // Nothing was burned: a sale is settling, say, or the site could not be asked. It can be tried again.
                    sheet.note.text = done?.toString().orEmpty()
                    again()
                }
            }
        }
    }
    sheet.hold.onHeld = ::begin
    dialog.show()
}

