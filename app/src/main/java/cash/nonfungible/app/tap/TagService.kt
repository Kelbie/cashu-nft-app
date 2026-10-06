package cash.nonfungible.app.tap

import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import cash.nonfungible.app.App

/** Carries a reader's commands to the door's tag and its answers back, while a door is open. */
class TagService : HostApduService() {
    private val tag get() = (application as App).tag

    override fun processCommandApdu(command: ByteArray?, extras: Bundle?): ByteArray =
        tag.process(command ?: ByteArray(0))

    override fun onDeactivated(reason: Int) = tag.left()
}
