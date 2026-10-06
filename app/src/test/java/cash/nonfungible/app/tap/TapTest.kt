package cash.nonfungible.app.tap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException

/** A phone held to a door: the reader's side run against the tag's, command for command. */
class TapTest {
    private val tag = DoorTag()
    private val heard = ArrayList<String>()
    private val commands = ArrayList<ByteArray>()
    private val link = Link { command ->
        commands += command
        tag.process(command)
    }

    private val request = "creqA" + "o2FpeDg0Zjk5M2NhZjUyOGZmMzc4".repeat(14)
    private val answer = "TICKET:" + "4F993CAF528FF378480ED5A33C642245" + ":" + "AB".repeat(321)

    private fun open(text: String? = request) {
        tag.text = text
        tag.onWritten = { heard += it }
    }

    @Test
    fun aPhoneReadsWhatTheDoorAsksAndTheDoorHearsItsAnswer() {
        open()
        val door = Tapped.read(link)
        assertEquals(request, door.text)
        door.write(answer)
        assertEquals(listOf(answer), heard)
    }

    /** A door names the app after its request, so a phone held to it with the app closed opens it. */
    @Test
    fun aDoorThatNamesItsAppStillSaysItsRequestFirst() {
        val naming = DoorTag("cash.nonfungible")
        naming.text = request
        naming.onWritten = { heard += it }
        val door = Tapped.read { naming.process(it) }
        assertEquals(request, door.text)
        door.write(answer)
        assertEquals(listOf(answer), heard)
        // As any phone reads it: a record of text, then a record of the app's name, and no more.
        val message = DoorTag.message(request, "cash.nonfungible")
        val records = message.copyOfRange(2, message.size)
        assertEquals(message.size - 2, (message[0].toInt() and 0xff shl 8) or (message[1].toInt() and 0xff))
        assertEquals(request, DoorTag.text(records))
        assertEquals(0x80, records[0].toInt() and 0xC0)
        val named = String(records, Charsets.ISO_8859_1)
        assert(named.endsWith("android.com:pkgcash.nonfungible")) { "The app is not named last" }
        val second = records.size - "android.com:pkgcash.nonfungible".length - 3
        assertEquals(0x54, records[second].toInt() and 0xff)
    }

    @Test
    fun noCommandCarriesMoreThanAShortOneCan() {
        open()
        Tapped.read(link).write(answer.repeat(3))
        assertEquals(1, heard.size)
        // Five bytes of header and the data: a command of 255 bytes of data is the longest there is.
        assert(commands.all { it.size <= 5 + DoorTag.CHUNK }) { "A command was too long" }
    }

    @Test
    fun aShortRequestAndALongAnswerBothArrive() {
        open("creqAshort")
        val door = Tapped.read(link)
        assertEquals("creqAshort", door.text)
        door.write(answer.repeat(10))
        assertEquals(answer.repeat(10), heard.single())
    }

    @Test
    fun aDoorThatIsNotOpenSaysNothing() {
        open(null)
        assertThrows(IOException::class.java) { Tapped.read(link) }
    }

    @Test
    fun whatIsInReachButIsNoDoorIsNotRead() {
        assertThrows(IOException::class.java) { Tapped.read { byteArrayOf(0x6A, 0x82.toByte()) } }
        assertThrows(IOException::class.java) { Tapped.read { ByteArray(0) } }
    }

    @Test
    fun aTapThatBreaksLeavesNoHalfAnswer() {
        open()
        var left = 5
        val breaking = Link { command ->
            // The phones part a few commands into the writing.
            if (command[1] == 0xD6.toByte() && left-- == 0) throw IOException("gone")
            tag.process(command)
        }
        val door = Tapped.read(breaking)
        assertThrows(IOException::class.java) { door.write(answer.repeat(4)) }
        tag.left()
        assertEquals(emptyList<String>(), heard)
        // The next phone is heard as if nothing had happened.
        Tapped.read(link).write(answer)
        assertEquals(listOf(answer), heard)
    }

    @Test
    fun aDoorHearsOneAnswerAfterAnother() {
        open()
        Tapped.read(link).write("first")
        tag.left()
        Tapped.read(link).write("second")
        assertEquals(listOf("first", "second"), heard)
    }

    /** A wallet of another make writes in small pieces, as some wallets do. */
    @Test
    fun aWalletThatWritesInSmallPiecesIsHeard() {
        open()
        Tapped.read(link)
        val message = DoorTag.message(answer)
        fun update(offset: Int, bytes: ByteArray) = tag.process(
            byteArrayOf(0x00, 0xD6.toByte(), (offset shr 8).toByte(), offset.toByte(), bytes.size.toByte()) + bytes
        )
        update(0, byteArrayOf(0, 0))
        for (at in 2 until message.size step 52) update(at, message.copyOfRange(at, minOf(message.size, at + 52)))
        assertEquals(emptyList<String>(), heard)
        update(0, message.copyOfRange(0, 2))
        assertEquals(listOf(answer), heard)
    }

    /** Some write the length and the message in one go. */
    @Test
    fun aWalletThatWritesItAllAtOnceIsHeard() {
        open()
        Tapped.read(link)
        val message = DoorTag.message("short answer")
        tag.process(byteArrayOf(0x00, 0xD6.toByte(), 0, 0, message.size.toByte()) + message)
        assertEquals(listOf("short answer"), heard)
    }

    @Test
    fun whatIsNotACommandIsRefusedAndNothingIsHeard() {
        open()
        assertArrayEquals(DoorTag.UNKNOWN, tag.process(ByteArray(0)))
        assertArrayEquals(DoorTag.UNKNOWN, tag.process(byteArrayOf(0x00, 0x20, 0, 0)))
        // Writing before the message's file is chosen, and past its end.
        assertArrayEquals(DoorTag.MISSING, tag.process(byteArrayOf(0x00, 0xD6.toByte(), 0, 0, 2, 0, 5)))
        Tapped.read(link)
        assertArrayEquals(DoorTag.MISSING, tag.process(byteArrayOf(0x00, 0xD6.toByte(), 0x7F, 0x00, 2, 1, 1)))
        // A command cut short of the data it says it carries.
        assertArrayEquals(DoorTag.MISSING, tag.process(byteArrayOf(0x00, 0xD6.toByte(), 0, 2, 9, 1)))
        assertEquals(emptyList<String>(), heard)
    }

    @Test
    fun aMessageThatIsNotTextIsNotHeard() {
        open()
        Tapped.read(link)
        // A record of a web address, not of text.
        val record = byteArrayOf(0xD1.toByte(), 1, 4, 'U'.code.toByte(), 4, 'a'.code.toByte(), '.'.code.toByte(), 'b'.code.toByte())
        tag.process(byteArrayOf(0x00, 0xD6.toByte(), 0, 0, (record.size + 2).toByte(), 0, record.size.toByte()) + record)
        assertEquals(emptyList<String>(), heard)
        assertNull(DoorTag.text(record))
        assertNull(DoorTag.text(byteArrayOf(0xD1.toByte(), 1)))
    }
}
