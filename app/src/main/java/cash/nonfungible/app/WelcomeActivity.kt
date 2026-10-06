package cash.nonfungible.app

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.lifecycle.lifecycleScope
import cash.nonfungible.app.databinding.ActivityWelcomeBinding
import cash.nonfungible.app.databinding.SheetGuestBinding
import cash.nonfungible.app.guest.Guest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * Asks what to call whoever holds the phone, and makes an account of that name. It is the one
 * thing asked before the app can be used: no key is shown, no mint chosen, no side taken. The
 * site hears of the account when it first has something to hold.
 *
 * A phone that was used before the app had accounts is asked the same, and everything it kept
 * becomes that account's. Somebody adding another account is asked it again.
 */
class WelcomeActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val app = application as App
        val another = intent.getBooleanExtra(EXTRA_ANOTHER, false) && app.accounts.all.isNotEmpty()
        binding.back.isVisible = another
        binding.brand.isVisible = !another
        binding.back.setOnClickListener { finish() }
        if (another) binding.title.setText(R.string.welcome_another)
        // What the phone kept before it had accounts: its guest's name, if they gave one.
        val before = !another && app.accounts.all.isEmpty() && used(this)
        binding.kept.isVisible = before
        if (savedInstanceState == null && before) binding.name.setText(app.accounts.before)
        fun named() = Account.named(binding.name.text.toString())
        binding.go.isEnabled = named() != null
        binding.name.doAfterTextChanged { binding.go.isEnabled = named() != null }
        fun begin() {
            val name = named() ?: return
            app.accounts.add(name)
            // A new account opens on what it holds, which is nothing yet, and on no door.
            Config(this).doorOpen = null
            Config(this).tab = null
            startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        binding.go.setOnClickListener { begin() }
        // A phone that already holds things names its account first: what it holds is not
        // something a key from elsewhere may be put in the place of.
        binding.restore.isVisible = !before
        binding.restore.setOnClickListener { restore(binding) }
        binding.name.setOnEditorActionListener { _, action, _ -> (action == EditorInfo.IME_ACTION_DONE).also { if (it) begin() } }
        if (savedInstanceState == null) binding.name.requestFocus()
    }

    /**
     * An account somebody had before, from the key they kept: its NFTs are whatever the site
     * lists under the key, and its wallet is the one the key opens. It is called what was typed,
     * or what the site calls it.
     */
    private fun restore(binding: ActivityWelcomeBinding) {
        val app = application as App
        val sheet = SheetGuestBinding.inflate(layoutInflater)
        val dialog = sheetOf(sheet.root)
        sheet.title.setText(R.string.welcome_restore)
        sheet.text.setText(R.string.welcome_restore_help)
        sheet.words.isVisible = true
        sheet.words.setHint(R.string.welcome_restore_hint)
        sheet.second.isVisible = true
        sheet.second.setText(R.string.collection_paste)
        sheet.second.setOnClickListener {
            getSystemService<ClipboardManager>()?.primaryClip?.getItemAt(0)?.coerceToText(this)?.let(sheet.words::setText)
        }
        fun key() = KEY.find(sheet.words.text.toString().trim().lowercase())?.value
        sheet.first.setText(R.string.welcome_restore_go)
        sheet.first.isEnabled = false
        sheet.words.doAfterTextChanged { sheet.first.isEnabled = key() != null }
        sheet.first.setOnClickListener {
            val key = key() ?: return@setOnClickListener
            sheet.first.isEnabled = false
            sheet.first.setText(R.string.welcome_restoring)
            sheet.note.isVisible = false
            lifecycleScope.launch {
                // The account is made to hold the key, and unmade if the key turns out to be none.
                val typed = Account.named(binding.name.text.toString())
                val account = app.accounts.add(typed ?: getString(R.string.welcome_restored))
                val guest = app.guestOf(account)
                guest.attach(binding.pages)
                val failed = try {
                    val called = guest.adopt(key)
                    if (typed == null) Account.named(called)?.takeIf { it != Guest.UNNAMED }?.let { app.accounts.rename(account, it) }
                    null
                } catch (e: IOException) {
                    e.message.orEmpty()
                } catch (e: TimeoutCancellationException) {
                    getString(R.string.guest_slow)
                }
                if (failed == null) {
                    dialog.dismiss()
                    Config(this@WelcomeActivity).doorOpen = null
                    Config(this@WelcomeActivity).tab = null
                    startActivity(Intent(this@WelcomeActivity, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_NEW_TASK))
                } else {
                    // Only what this attempt made is unmade. The page kept nothing: it keeps a key
                    // only once the site has said whose it is.
                    app.accounts.remove(account)
                    app.dropped(account)
                    sheet.note.text = failed
                    sheet.note.isVisible = true
                    sheet.first.isEnabled = true
                    sheet.first.setText(R.string.welcome_restore_go)
                }
            }
        }
        dialog.show()
    }

    companion object {
        private val KEY = Regex("^[0-9a-f]{64}$")

        /** Asked by somebody who has an account and wants one more. */
        const val EXTRA_ANOTHER = "another"

        /** Whether this phone kept anything before it had accounts: a guest's NFTs, or a collection. */
        private fun used(context: Context): Boolean =
            context.getSharedPreferences("guest", Context.MODE_PRIVATE).all.isNotEmpty() ||
                context.getSharedPreferences("collections", Context.MODE_PRIVATE).all.isNotEmpty()
    }
}
