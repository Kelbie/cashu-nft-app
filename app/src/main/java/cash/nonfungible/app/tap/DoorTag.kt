package cash.nonfungible.app.tap

/**
 * A door as something a phone can be held to: an NFC Forum Type 4 Tag whose NDEF file holds the
 * door's request as a Text record, as a Cashu point of sale shows a NUT-18 request. A wallet
 * reads the request and writes its answer back as a Text record in the same tap, so the wallet
 * needs no network of its own. After the request the tag names this app, as an Android
 * Application Record, so that a phone held to the door with the app closed opens it there.
 * Bytes in, bytes out: what carries them is the service's business.
 */
class DoorTag(
    /** The app a phone opens when it is held to the door with nothing open: this one's own name. */
    private val opens: String? = null,
) {
    /** What the tag says to a reader: the door's request, or nothing while no door is open. */
    @Volatile
    var text: String? = null

    /** Hears each whole message a reader wrote, on the thread that fed the bytes. */
    @Volatile
    var onWritten: ((String) -> Unit)? = null

    /** The file a reader has chosen to read: the capability container or the request. */
    private var chosen: ByteArray? = null
    private var writable = false
    private val written = ByteArray(SIZE)

    /** How far into the file a reader has written since it last emptied it. */
    private var reached = 0

    /** Answers one command of a reader. */
    @Synchronized
    fun process(command: ByteArray): ByteArray {
        if (command.size < 4 || command[0] != 0x00.toByte()) return UNKNOWN
        val p1 = command[2].toInt() and 0xff
        val p2 = command[3].toInt() and 0xff
        return when (command[1]) {
            SELECT -> select(p1, command)
            READ -> read(p1 shl 8 or p2, command)
            UPDATE -> update(p1 shl 8 or p2, command)
            else -> UNKNOWN
        }
    }

    /** The reader has gone: the next one starts from nothing. */
    @Synchronized
    fun left() {
        chosen = null
        writable = false
        reached = 0
    }

    private fun select(p1: Int, command: ByteArray): ByteArray {
        val named = data(command) ?: return MISSING
        if (p1 == 0x04) return if (named.contentEquals(APPLICATION)) OK else MISSING
        writable = false
        chosen = when {
            named.contentEquals(CONTAINER_ID) -> CONTAINER
            // A door that is not open has nothing to say, not an empty message.
            named.contentEquals(FILE_ID) -> text?.let { message(it, opens) }?.also {
                writable = true
                reached = 0
            }
            else -> null
        }
        return if (chosen == null) MISSING else OK
    }

    private fun read(offset: Int, command: ByteArray): ByteArray {
        val file = chosen ?: return MISSING
        if (command.size < 5 || offset >= file.size) return MISSING
        val wanted = (command[4].toInt() and 0xff).let { if (it == 0) 256 else it }
        return file.copyOfRange(offset, minOf(file.size, offset + wanted)) + OK
    }

    /**
     * A reader writes a message as the standard has it: the length is set to nothing, the
     * message is written after it, and the length is written last. Some write both at once.
     * Either way the message is whole when the length is there and the bytes it counts are.
     */
    private fun update(offset: Int, command: ByteArray): ByteArray {
        val bytes = data(command) ?: return MISSING
        if (!writable || offset + bytes.size > written.size) return MISSING
        bytes.copyInto(written, offset)
        reached = maxOf(reached, offset + bytes.size)
        val length = (written[0].toInt() and 0xff shl 8) or (written[1].toInt() and 0xff)
        if (offset == 0 && length == 0) reached = 2
        if (reached >= 2 && length > 0 && reached >= 2 + length) {
            val said = text(written.copyOfRange(2, 2 + length))
            written.fill(0)
            reached = 0
            if (said != null) onWritten?.invoke(said)
        }
        return OK
    }

    /** The data of a command that carries some, or null when the command is cut short. */
    private fun data(command: ByteArray): ByteArray? {
        if (command.size < 5) return null
        val length = command[4].toInt() and 0xff
        if (command.size < 5 + length) return null
        return command.copyOfRange(5, 5 + length)
    }

    companion object {
        private const val SELECT = 0xA4.toByte()
        private const val READ = 0xB0.toByte()
        private const val UPDATE = 0xD6.toByte()

        /** The most one command reads or writes: what fits a short command with room to spare. */
        const val CHUNK = 0xF0
        private const val SIZE = 0x4000

        val OK = byteArrayOf(0x90.toByte(), 0x00)
        val MISSING = byteArrayOf(0x6A, 0x82.toByte())
        val UNKNOWN = byteArrayOf(0x6D, 0x00)

        /** The NDEF application every Type 4 Tag answers to. */
        val APPLICATION = byteArrayOf(0xD2.toByte(), 0x76, 0x00, 0x00, 0x85.toByte(), 0x01, 0x01)
        val CONTAINER_ID = byteArrayOf(0xE1.toByte(), 0x03)
        val FILE_ID = byteArrayOf(0xE1.toByte(), 0x04)

        /** Says where the message is and how much of it one command may read or write. */
        val CONTAINER = byteArrayOf(
            0x00, 0x0F, 0x20,
            0x00, CHUNK.toByte(),
            0x00, CHUNK.toByte(),
            0x04, 0x06, FILE_ID[0], FILE_ID[1],
            (SIZE shr 8).toByte(), SIZE.toByte(),
            0x00, 0x00,
        )

        /**
         * An NDEF file: its length, then a Text record and, when `opens` names an app, an
         * Android Application Record after it.
         */
        fun message(text: String, opens: String? = null): ByteArray {
            val said = byteArrayOf(2) + "en".toByteArray(Charsets.US_ASCII) + text.toByteArray()
            // A well-known type, "T": text. Then, of an external type, the app to open.
            val records = record(first = true, last = opens == null, WELL_KNOWN, "T", said) +
                (opens?.let { record(first = false, last = true, EXTERNAL, "android.com:pkg", it.toByteArray()) } ?: ByteArray(0))
            return byteArrayOf((records.size shr 8).toByte(), records.size.toByte()) + records
        }

        private fun record(first: Boolean, last: Boolean, format: Int, type: String, payload: ByteArray): ByteArray {
            val short = payload.size <= 255
            val length = if (short) byteArrayOf(payload.size.toByte()) else byteArrayOf(
                (payload.size ushr 24).toByte(), (payload.size ushr 16).toByte(),
                (payload.size ushr 8).toByte(), payload.size.toByte(),
            )
            val flags = (if (first) 0x80 else 0) or (if (last) 0x40 else 0) or (if (short) 0x10 else 0) or format
            return byteArrayOf(flags.toByte(), type.length.toByte()) + length + type.toByteArray(Charsets.US_ASCII) + payload
        }

        private const val WELL_KNOWN = 0x01
        private const val EXTERNAL = 0x04

        /** The text of a message's first record, if that is a Text record. Null for anything else. */
        fun text(message: ByteArray): String? {
            if (message.size < 3) return null
            val flags = message[0].toInt() and 0xff
            val typeLength = message[1].toInt() and 0xff
            var at = 2
            val payloadLength = if (flags and 0x10 != 0) {
                message[at++].toInt() and 0xff
            } else {
                if (message.size < at + 4) return null
                var long = 0
                repeat(4) { long = long shl 8 or (message[at++].toInt() and 0xff) }
                long
            }
            val idLength = if (flags and 0x08 != 0) {
                if (message.size <= at) return null
                message[at++].toInt() and 0xff
            } else 0
            if (payloadLength < 1 || message.size < at + typeLength + idLength + payloadLength) return null
            val wellKnown = flags and 0x07 == 0x01
            if (!wellKnown || typeLength != 1 || message[at] != 'T'.code.toByte()) return null
            at += typeLength + idLength
            val status = message[at].toInt() and 0xff
            val skip = 1 + (status and 0x3f)
            if (skip > payloadLength) return null
            val charset = if (status and 0x80 != 0) Charsets.UTF_16 else Charsets.UTF_8
            return String(message, at + skip, payloadLength - skip, charset)
        }
    }
}
