package cash.nonfungible.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.getSystemService
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import cash.nonfungible.app.databinding.ActivitySettingsBinding
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * What the account in use is called and the key to what is its, how a door kept on this phone
 * behaves, and, last and apart, the two ways to delete: this account, or everything.
 *
 * Nothing about any one collection is here. A collection is managed from its own page.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var config: Config
    private val app get() = application as App

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        config = Config(this)
        val account = app.account
        if (savedInstanceState == null) {
            binding.name.setText(account.name)
            binding.door.setText(config.door)
            binding.relays.setText(config.debugRelays)
            binding.site.setText(config.debugSite)
            binding.sound.isChecked = config.sound
            binding.express.isChecked = config.express
        }
        binding.relaysField.isVisible = BuildConfig.DEBUG
        binding.back.setOnClickListener { finish() }
        binding.save.setOnClickListener {
            // The site is told the new name the next time it is asked anything.
            Account.named(binding.name.text.toString())?.let { app.accounts.rename(account, it) }
            config.save(
                binding.door.text.toString(), binding.relays.text.toString(), binding.site.text.toString(),
                binding.sound.isChecked, binding.express.isChecked,
            )
            finish()
        }
        // An account's NFTs and money are in a collection whose only key is on this phone.
        binding.key.label.setText(R.string.guest_key)
        binding.key.root.isVisible = app.guest.me != null
        binding.key.root.setOnClickListener {
            val site = config.site.substringAfter("://")
            confirm(getString(R.string.settings_key_title), getString(R.string.guest_key_message, site), getString(R.string.settings_key_confirm)) {
                copyKey()
            }
        }
        binding.deleteAccount.label.text = getString(R.string.settings_delete_account, account.name)
        binding.deleteAccount.label.setTextColor(getColor(R.color.bad))
        binding.deleteAccount.root.isVisible = app.accounts.all.size > 1
        binding.deleteAccount.root.setOnClickListener {
            startActivity(Intent(this, DeleteActivity::class.java).putExtra(DeleteActivity.EXTRA_ACCOUNT, account.id))
        }
        binding.deleteAll.label.setText(R.string.settings_delete_all)
        binding.deleteAll.label.setTextColor(getColor(R.color.bad))
        binding.deleteAll.root.setOnClickListener { startActivity(Intent(this, DeleteActivity::class.java)) }
    }

    override fun onStart() {
        super.onStart()
        app.guest.attach(binding.pages)
    }

    override fun onDestroy() {
        app.guest.detach(binding.pages)
        super.onDestroy()
    }

    /** Puts the account's key on the clipboard, marked as something not to be shown. */
    private fun copyKey() {
        lifecycleScope.launch {
            val said = try {
                val key = app.guest.key() ?: throw IOException("this phone does not hold it")
                val copied = ClipData.newPlainText("key", key)
                copied.description.extras = PersistableBundle().apply { putBoolean(SENSITIVE, true) }
                getSystemService<ClipboardManager>()?.setPrimaryClip(copied)
                getString(R.string.settings_key_copied)
            } catch (e: IOException) {
                getString(R.string.settings_key_failed, e.message)
            } catch (e: TimeoutCancellationException) {
                getString(R.string.settings_key_failed, e.message)
            }
            binding.key.value.text = said
        }
    }

    companion object {
        // What tells the phone's keyboard and its clipboard preview not to show what was copied.
        const val SENSITIVE = "android.content.extra.IS_SENSITIVE"
    }
}
