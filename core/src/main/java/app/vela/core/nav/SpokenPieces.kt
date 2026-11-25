package app.vela.core.nav

import app.vela.core.i18n.NavStrings
import app.vela.core.i18n.NavStringsRegistry
import app.vela.core.voice.SpeechText

/**
 * Phrases the neural voice keeps rendered for the whole drive, so a line made of them needs no
 * render: "Rerouting", said the moment the car leaves the route, and the distance clause of an
 * approach line ("In 500 feet,"). The voice renders a line phrase by phrase and stores each
 * phrase, so with these kept a line whose distance moved, or the new route's first line after a
 * reroute, renders only its instruction.
 *
 * A language whose approach line has no clause break ("Через 500 метров поверните") has no
 * distance clause to keep, only its rerouting line.
 */
object SpokenPieces {
    /** From 100 feet to a mile, every distance [NavStrings.spokenDistance] can say: feet in
     *  fifties, then quarter miles, "0.9 miles" and a mile. */
    private val IMPERIAL_M = (31..1650).map { it.toDouble() }

    /** Meters in fifties, then the first kilometers. */
    private val METRIC_M = (50..900 step 50).map { it.toDouble() } + listOf(1000.0, 1500.0, 2000.0)

    /** The rerouting line, then the distance clauses, nearest first. */
    fun forDrive(imperial: Boolean, words: NavStrings = NavStringsRegistry.current()): List<String> =
        listOf(words.rerouting()) + (if (imperial) IMPERIAL_M else METRIC_M)
            .map { words.spokenDistance(it, imperial) }
            .distinct()
            .mapNotNull { distanceClause(words, it) }

    /** The phrase [words] say a distance in, cut where the voice cuts the approach line, or null
     *  when the line is one phrase. */
    fun distanceClause(words: NavStrings, distance: String): String? {
        val frags = SpeechText.speechFragments(words.inThen(distance, PROBE), 0f, 0f).map { it.first }
        if (frags.size < 2 || frags.last() != PROBE) return null
        return frags.dropLast(1).singleOrNull()
    }

    private const val PROBE = "go"
}
