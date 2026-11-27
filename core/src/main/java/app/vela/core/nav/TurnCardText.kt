package app.vela.core.nav

import app.vela.core.util.RoadNameShort

/**
 * What the turn card shows in place of the instruction sentence, so the step reads at a glance
 * the way Google's card does: [main] is the one large line, the place you are going (the road
 * entered, or the sign's towns, which [towns] says), [lead] is a short line above it and [sub] a
 * short line below.
 * [signRoads] are the sign's streets, which Google's card writes beside the exit tab ("519B J St"
 * over "Downtown"). Route numbers the card already draws as shields are left out of the text.
 *
 * [of] works on the finished instruction text in any language. The road is found by its own
 * text; the sign destinations come from the language's marker ([app.vela.core.i18n.NavStrings.signDestination]).
 */
data class TurnCardText(val lead: String, val main: String, val sub: String, val signRoads: String = "", val towns: Boolean = false) {
    companion object {
        /**
         * The card text for [full] (the whole instruction, sign cities after a colon included), or
         * null when the card should keep the sentence. [road] is the road entered as it stands in
         * [full]. [signDest] is the text after the language's destination marker, null when the
         * language has none or the step has no sign. [shields] are the route labels the card draws
         * as chips ("I 80", "EXIT 72"); a destination that is one of them is dropped from the text.
         *
         * A turn onto a named road: lead = the words before the road, main = the road, sub = the
         * words after it. A ramp, exit, fork or merge with a sign: the destinations that are not
         * shields, joined with " / " as Google's card does; the towns are main and the streets,
         * shortened as a sign writes them, are [signRoads] ("J St" beside the tab, "Downtown"
         * under it). A sign with streets only puts them in main. lead and sub are empty. A sign
         * whose only destination is a shield gives an empty main: the chips say it all.
         */
        fun of(full: String, road: String?, signDest: String?, shields: List<String>): TurnCardText? {
            val keys = shields.map(::key).filter { it.isNotEmpty() }
            fun onShield(s: String): Boolean {
                val k = key(s)
                // "I 80 E" is the "I 80" shield with its direction; "I 50" is not "I 5".
                return k.isNotEmpty() && keys.any { sk ->
                    k == sk || (k.startsWith(sk) && k.length - sk.length <= 2 && k.substring(sk.length).all(Char::isLetter))
                }
            }
            val r = road?.trim().orEmpty()
            if (r.isNotEmpty() && !onShield(r)) {
                split(full, r)?.let { return it }
            }
            val d = signDest?.trim().orEmpty()
            if (d.isEmpty()) return null
            val items = d.split(':', ',').map { it.trim() }.filter { it.isNotEmpty() }
            val places = items.filterNot(::onShield)
            val (streets, towns) = places.partition(RoadNameShort::isStreet)
            val shortStreets = streets.map(RoadNameShort::shorten)
            return if (towns.isEmpty()) TurnCardText(lead = "", main = shortStreets.joinToString(SEP), sub = "")
            else TurnCardText(lead = "", main = towns.joinToString(SEP), sub = "", signRoads = shortStreets.joinToString(SEP), towns = true)
        }

        /**
         * [headline] cut around [road], or null when it does not contain the road as a whole word
         * (a ramp named only by its sign, a romanized name the text does not use, "Elm" inside
         * "Elmwood"). The road is matched case-sensitively at its first whole-word occurrence.
         */
        fun split(headline: String, road: String?): TurnCardText? {
            val r = road?.trim().orEmpty()
            if (r.isEmpty()) return null
            var from = 0
            while (true) {
                val at = headline.indexOf(r, from)
                if (at < 0) return null
                val end = at + r.length
                val openBefore = at == 0 || !headline[at - 1].isLetterOrDigit()
                val openAfter = end == headline.length || !headline[end].isLetterOrDigit()
                if (openBefore && openAfter) {
                    return TurnCardText(
                        lead = headline.substring(0, at).trim().trimEnd(',', ':'),
                        main = r,
                        sub = headline.substring(end).trim().trimStart(',', ':').trim(),
                    )
                }
                from = at + 1
            }
        }

        /** Between two sign destinations, as Google's card writes them ("Sacramento / Reno"). */
        private const val SEP = " / "

        /** "I-80 E", "I 80" and "Exit 72" compared as route labels: letters and digits, upper case. */
        private fun key(label: String): String = label.uppercase().filter { it.isLetterOrDigit() }
    }
}
