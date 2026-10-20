package app.vela.core.util

/**
 * A road name shortened the way a street sign does it: "Inglewood Road Northeast" would not fit
 * the small label beside the speed readout, "Inglewood Rd NE" does. English street words only;
 * a name in any other language has none of them and comes back unchanged.
 *
 * The first word is never shortened (it is the name: "North Avenue" stays "North Ave", "Court
 * Street" stays "Court St"), and a direction is shortened only as the first or last word of a name
 * of three words or more ("Northeast 8th Street" is "NE 8th St").
 *
 * On screen every road name goes through it: the pill under the arrow, the turn card and its
 * "then" line ([inText]), the car's step (issue #837). The voice keeps the full words.
 */
object RoadNameShort {
    private val TYPES = mapOf(
        "street" to "St", "avenue" to "Ave", "boulevard" to "Blvd", "road" to "Rd", "drive" to "Dr",
        "lane" to "Ln", "court" to "Ct", "place" to "Pl", "highway" to "Hwy", "parkway" to "Pkwy",
        "terrace" to "Ter", "circle" to "Cir", "trail" to "Trl", "expressway" to "Expy", "freeway" to "Fwy",
        "square" to "Sq", "turnpike" to "Tpke", "junction" to "Jct",
    )
    private val DIRECTIONS = mapOf(
        "north" to "N", "south" to "S", "east" to "E", "west" to "W",
        "northeast" to "NE", "northwest" to "NW", "southeast" to "SE", "southwest" to "SW",
    )

    fun shorten(name: String): String {
        val words = name.trim().split(' ').filter { it.isNotEmpty() }
        if (words.size < 2) return name
        return words.mapIndexed { i, w ->
            val key = w.lowercase()
            val edge = i == 0 || i == words.lastIndex
            when {
                words.size >= 3 && edge && key in DIRECTIONS -> DIRECTIONS.getValue(key)
                i > 0 && key in TYPES -> TYPES.getValue(key)
                else -> w
            }
        }.joinToString(" ")
    }

    /** [text] with its one [road] name shortened in place: "Turn right onto Court Street" with the
     *  road "Court Street" is "Turn right onto Court St". The name is shortened on its own, so its
     *  first word is kept; the text is unchanged when the road is not in it (a romanized name, a
     *  number used instead). A compass word after a route number is cut to its letter wherever it
     *  stands, a sign's destination included ("toward US 169 North" is "toward US 169 N", as the
     *  sign reads; issue #837). A direction in a town's name ("toward North Bend") stays. */
    fun inText(text: String, road: String?): String {
        val t = routeCardinals(text)
        if (road.isNullOrBlank()) return t
        val short = shorten(road)
        if (short == road) return t
        val at = t.indexOf(road)
        return if (at < 0) t else t.substring(0, at) + short + t.substring(at + road.length)
    }

    /** "US 169 North" to "US 169 N", "I-44 Westbound" to "I-44 W": a compass word straight after a
     *  route number (letters, then digits), English only. */
    fun routeCardinals(text: String): String = ROUTE_CARDINAL.replace(text) { m ->
        m.groupValues[1] + " " + CARDINAL_LETTER.getValue(m.groupValues[2].lowercase().removeSuffix("bound"))
    }

    private val CARDINAL_LETTER = mapOf("north" to "N", "south" to "S", "east" to "E", "west" to "W")
    private val ROUTE_CARDINAL = Regex("""\b((?:[A-Z]{1,4}[ -]?)\d{1,4}[A-Z]?)\s+(North|South|East|West|Northbound|Southbound|Eastbound|Westbound)\b""")
}
