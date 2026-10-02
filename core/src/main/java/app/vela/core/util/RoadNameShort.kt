package app.vela.core.util

/**
 * A road name shortened the way a street sign does it: "Inglewood Road Northeast" would not fit
 * the small label beside the speed readout, "Inglewood Rd NE" does. English street words only;
 * a name in any other language has none of them and comes back unchanged.
 *
 * The first word is never shortened (it is the name: "North Avenue" stays "North Ave", "Court
 * Street" stays "Court St"), and a direction is shortened only as the first or last word of a name
 * of three words or more ("Northeast 8th Street" is "NE 8th St").
 */
object RoadNameShort {
    private val TYPES = mapOf(
        "street" to "St", "avenue" to "Ave", "boulevard" to "Blvd", "road" to "Rd", "drive" to "Dr",
        "lane" to "Ln", "court" to "Ct", "place" to "Pl", "highway" to "Hwy", "parkway" to "Pkwy",
        "terrace" to "Ter", "circle" to "Cir", "trail" to "Trl", "expressway" to "Expy", "freeway" to "Fwy",
        "square" to "Sq", "turnpike" to "Tpke",
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
}
