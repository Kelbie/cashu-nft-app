package cash.nonfungible.app.tap

import java.io.IOException

/** Something in reach that answers a command: an NFC tag, or a stand-in for one. */
fun interface Link {
    @Throws(IOException::class)
    fun transceive(command: ByteArray): ByteArray
}

/**
 * A door a phone is being held to, from the phone's side: what the door asks, and the way to
 * hand it an answer while the two are still together. The reading and writing are those of any
 * NFC Forum Type 4 Tag.
 */
class Tapped private constructor(
    private val link: Link,
    private val most: Int,
    private val room: Int,
    /** What the door says: its request. */
    val text: String,
) {
    /** Writes an answer to the door. The door hears it once the last byte is there. */
    @Throws(IOException::class)
    fun write(answer: String) {
        val message = DoorTag.message(answer)
        if (message.size > room) throw IOException("The answer is too long for the door")
        // The length is emptied first and written last, so a tap that breaks leaves no half message.
        update(0, byteArrayOf(0, 0))
        var at = 2
        while (at < message.size) {
            val end = minOf(message.size, at + most)
            update(at, message.copyOfRange(at, end))
            at = end
        }
        update(0, message.copyOfRange(0, 2))
    }

    private fun update(offset: Int, bytes: ByteArray) {
        link.ask(byteArrayOf(0x00, 0xD6.toByte(), (offset shr 8).toByte(), offset.toByte(), bytes.size.toByte()) + bytes)
    }

    companion object {
        /** Reads what a door says. Throws when what is in reach is not a door with a request. */
        @Throws(IOException::class)
        fun read(link: Link): Tapped {
            link.ask(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x07) + DoorTag.APPLICATION + 0x00)
            link.choose(DoorTag.CONTAINER_ID)
            val container = link.read(0, 15)
            if (container.size < 15 || container[7] != 0x04.toByte()) throw IOException("Not a door")
            val perRead = (container.word(3)).coerceIn(1, 255)
            val perWrite = (container.word(5)).coerceIn(1, 255)
            val room = container.word(11)
            if (container[14] != 0x00.toByte()) throw IOException("This door takes no answer")
            link.choose(byteArrayOf(container[9], container[10]))
            val length = link.read(0, 2).takeIf { it.size == 2 }?.word(0) ?: throw IOException("Not a door")
            if (length == 0 || length + 2 > room) throw IOException("The door asks nothing")
            var message = ByteArray(0)
            while (message.size < length) {
                val part = link.read(2 + message.size, minOf(perRead, length - message.size))
                if (part.isEmpty()) throw IOException("The door stopped answering")
                message += part
            }
            val text = DoorTag.text(message) ?: throw IOException("The door asks nothing a phone can read")
            return Tapped(link, perWrite, room, text)
        }

        private fun ByteArray.word(at: Int): Int = (this[at].toInt() and 0xff shl 8) or (this[at + 1].toInt() and 0xff)

        private fun Link.choose(file: ByteArray) {
            ask(byteArrayOf(0x00, 0xA4.toByte(), 0x00, 0x0C, 0x02) + file)
        }

        private fun Link.read(offset: Int, length: Int): ByteArray =
            ask(byteArrayOf(0x00, 0xB0.toByte(), (offset shr 8).toByte(), offset.toByte(), length.toByte()))

        /** What a command is answered with, without its two bytes of status. Throws unless they say all is well. */
        private fun Link.ask(command: ByteArray): ByteArray {
            val answer = transceive(command)
            val ok = answer.size >= 2 && answer[answer.size - 2] == 0x90.toByte() && answer[answer.size - 1] == 0x00.toByte()
            if (!ok) throw IOException("The door refused a command")
            return answer.copyOf(answer.size - 2)
        }
    }
}
