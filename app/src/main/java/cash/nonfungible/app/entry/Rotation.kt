package cash.nonfungible.app.entry

/** What became of an answer when it was matched to the codes a door has shown. */
sealed interface Taken {
    /** It answers a code that may still be answered. */
    class Live(val asked: Asked) : Taken

    /** It answers a code that was replaced too long ago. */
    data object Late : Taken

    /** It answers nothing this door showed, or a code already answered. */
    data object None : Taken
}

/**
 * The codes a door shows, one after another, like an authenticator's. Each is on screen for a
 * period and may still be answered for one more, so a holder who scanned just before the code
 * changed is not turned away. A code is answered once, and an answer brings on a new code.
 * Answers come in on more than one thread, so each of these is done whole.
 */
class Rotation(val periodMs: Long = 30_000, private val next: () -> Asked) {
    private class Shown(val asked: Asked, val at: Long)

    private val shown = ArrayDeque<Shown>()

    /** Whether an answer has been taken since the code on screen was made: it is then replaced. */
    private var answered = false

    /** The code to show at `now`: a new one once the last has had its period, or an answer has been taken. */
    @Synchronized
    fun current(now: Long): Asked {
        if (!answered) shown.lastOrNull()?.takeIf { now - it.at < periodMs }?.let { return it.asked }
        answered = false
        shown.removeAll { now - it.at >= REMEMBERED * periodMs }
        return next().also { shown.addLast(Shown(it, now)) }
    }

    /** How long the code on screen has left at `now`. */
    @Synchronized
    fun left(now: Long): Long =
        shown.lastOrNull()?.let { (periodMs - (now - it.at)).coerceIn(0, periodMs) } ?: 0

    /** The code with this id, if the door has shown it and no answer has spent it. */
    @Synchronized
    fun shown(id: String): Asked? = shown.firstOrNull { it.asked.id == id }?.asked

    /** Matches an answer to a code, and spends nothing: for an answer that is yet to be borne out. */
    @Synchronized
    fun match(answer: Answer, now: Long): Taken {
        val code = shown.firstOrNull { it.asked.isAnsweredBy(answer) } ?: return Taken.None
        return if (now - code.at < 2 * periodMs) Taken.Live(code.asked) else Taken.Late
    }

    /** Matches an answer to a code and spends that code. The door then shows a new one. */
    @Synchronized
    fun take(answer: Answer, now: Long): Taken {
        val code = shown.firstOrNull { it.asked.isAnsweredBy(answer) } ?: return Taken.None
        shown.remove(code)
        answered = true
        return if (now - code.at < 2 * periodMs) Taken.Live(code.asked) else Taken.Late
    }

    private companion object {
        // Codes are remembered for a while after they stop counting, to tell a holder who
        // was slow from a message that answers nothing.
        const val REMEMBERED = 10
    }
}
