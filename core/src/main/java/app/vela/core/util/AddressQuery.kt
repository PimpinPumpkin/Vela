package app.vela.core.util

/**
 * A typed street address ("912 Miller Dr") and the test for whether a search result IS that
 * address. The test is on whole words: the house number and the street's first distinguishing word
 * must both be words of the result's name or address. A substring test called a result a match
 * whenever the digits appeared anywhere, and they appear in ZIP codes ("616" in "95616") and in
 * neighbors' numbers ("12" in "1200"), so a list of nearby businesses read as "already has the
 * address" and the geocoder was never asked (issue #638).
 */
object AddressQuery {
    private val LEAD = Regex("""^\s*(\d+[a-zA-Z]?)\s+(.+)$""")
    private val SPLIT = Regex("""[^\p{L}\p{N}]+""")
    private val DIRECTIONS = setOf("n", "s", "e", "w", "ne", "nw", "se", "sw", "north", "south", "east", "west")
    private val TYPES = setOf(
        "st", "street", "ave", "av", "avenue", "rd", "road", "dr", "drive", "ln", "lane", "blvd", "boulevard",
        "ct", "court", "pl", "place", "way", "cir", "circle", "ter", "terrace", "hwy", "highway", "pkwy", "parkway",
        "trl", "trail", "loop", "apt", "unit", "suite", "ste",
    )

    /** The house number and the street's first distinguishing word, lowercase; null when [query]
     *  does not start with a number followed by a street. A street made only of a direction and a
     *  type ("12 North St") keeps its first word. */
    fun parse(query: String): Pair<String, String>? {
        val m = LEAD.find(query) ?: return null
        val words = m.groupValues[2].lowercase().split(SPLIT).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        val street = words.firstOrNull { it !in DIRECTIONS && it !in TYPES } ?: words.first()
        return m.groupValues[1].lowercase() to street
    }

    /** True when a result named [name] at [address] is the address typed in [query]. False when
     *  the query is not an address. */
    fun matches(query: String, name: String?, address: String?): Boolean {
        val (number, street) = parse(query) ?: return false
        val words = ("${name.orEmpty()} ${address.orEmpty()}").lowercase().split(SPLIT)
        return number in words && street in words
    }
}
