package app.vela.core.nav

import app.vela.core.model.LatLng
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Initial-heading helpers so spoken guidance can say "Head east on …" like Google,
 * rather than Google's markup-only "Head toward …". */
object Heading {
    private val CARDINALS =
        listOf("north", "northeast", "east", "southeast", "south", "southwest", "west", "northwest")
    private val HAS_CARDINAL =
        Regex("\\b(north|south|east|west|north-?east|north-?west|south-?east|south-?west)\\b", RegexOption.IGNORE_CASE)

    /** 8-point cardinal of the route's first leg, or null if it can't be determined. */
    fun initialCardinal(polyline: List<LatLng>): String? {
        if (polyline.size < 2) return null
        val a = polyline.first()
        // Look a few points ahead so a jittery first segment doesn't pick a wrong way.
        val b = polyline[minOf(4, polyline.size - 1)]
        if (a.lat == b.lat && a.lng == b.lng) return null
        val dLon = Math.toRadians(b.lng - a.lng)
        val la = Math.toRadians(a.lat)
        val lb = Math.toRadians(b.lat)
        val y = sin(dLon) * cos(lb)
        val x = cos(la) * sin(lb) - sin(la) * cos(lb) * cos(dLon)
        val bearing = (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        return CARDINALS[((bearing + 22.5) / 45.0).toInt() % 8]
    }

    /** Inject the cardinal into a "Head [toward] <road>" instruction → "Head east on
     * <road>". Leaves instructions that already name a direction (or aren't "Head …")
     * untouched. */
    fun withCardinal(instruction: String, polyline: List<LatLng>): String {
        if (instruction.isBlank() || HAS_CARDINAL.containsMatchIn(instruction)) return instruction
        val card = initialCardinal(polyline) ?: return instruction
        return when {
            // OSRM-primary routing phrases DEPART as "Head out on <road>" (osrmPhrase) - swap the whole
            // "out on" for the cardinal. Without this branch it fell to the bare-"Head" rewrite below,
            // which doubled the words into "Head east on OUT ON <road>" - the reported "it says head
            // out on out on twice when starting navigation". Checked longest-form-first on purpose.
            instruction.startsWith("Head out on", ignoreCase = true) ->
                instruction.replaceFirst(Regex("^Head out on", RegexOption.IGNORE_CASE), "Head $card on")
            instruction.startsWith("Head out", ignoreCase = true) ->
                instruction.replaceFirst(Regex("^Head out", RegexOption.IGNORE_CASE), "Head $card")
            instruction.startsWith("Head toward", ignoreCase = true) ->
                instruction.replaceFirst(Regex("^Head toward", RegexOption.IGNORE_CASE), "Head $card on")
            instruction.startsWith("Head on", ignoreCase = true) ->
                instruction.replaceFirst(Regex("^Head on", RegexOption.IGNORE_CASE), "Head $card on")
            instruction.startsWith("Head", ignoreCase = true) ->
                instruction.replaceFirst(Regex("^Head", RegexOption.IGNORE_CASE), "Head $card on")
            else -> instruction
        }
    }
}
