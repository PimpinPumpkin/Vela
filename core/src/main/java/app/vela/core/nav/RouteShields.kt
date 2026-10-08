package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.Route

/**
 * Route number shields on the route line during a drive, the way Google marks the road being
 * driven: one soon after joining a numbered road, then one every few kilometers. The basemap's own
 * shields are off in a drive (every numbered road in view carried them, the other carriageway's
 * too), which left the driven road with no number on the map at all.
 *
 * Places come from the steps: a step's leg carries the number of the road it entered and the
 * places that number changes with no turn ([app.vela.core.model.Maneuver.renames]). Step lengths
 * are scaled to the drawn line, so a place is right to within the difference between the two.
 */
object RouteShields {
    /** The first shield of a stretch sits this far in, clear of the turn that entered it. */
    const val FIRST_AFTER_M = 350.0
    /** A mile apart: at freeway speed one is in view about a third of the time. */
    const val EVERY_M = 1_600.0
    /** A numbered stretch shorter than this gets none. */
    const val MIN_STRETCH_M = 900.0
    /** None this close to the stretch's end, where the next turn's callout goes. */
    const val END_CLEAR_M = 300.0
    /** The longest text a shield image holds. */
    const val MAX_TEXT = 6

    data class Shield(val at: LatLng, val type: ShieldType, val text: String)

    private data class Stretch(val fromM: Double, var toM: Double, val ref: String)

    fun points(route: Route): List<Shield> {
        val line = route.drawPolyline ?: route.polyline
        val ms = route.maneuvers
        if (line.size < 2 || ms.size < 2) return emptyList()
        val cum = RouteProjection.cumulative(line)
        val stepsM = ms.sumOf { it.distanceMeters }
        if (stepsM <= 0.0) return emptyList()
        val scale = cum.last() / stepsM
        val stretches = ArrayList<Stretch>()
        fun add(fromM: Double, toM: Double, ref: String?) {
            if (toM <= fromM) return
            val r = ref?.trim().orEmpty()
            val last = stretches.lastOrNull()
            if (last != null && key(last.ref) == key(r) && kotlin.math.abs(last.toM - fromM) < 1.0) last.toM = toM
            else stretches += Stretch(fromM, toM, r)
        }
        var at = 0.0
        for (m in ms) {
            val legEnd = at + m.distanceMeters * scale
            var from = at
            var ref = m.ref
            for (x in m.renames) {
                val cut = (at + x.atMeters * scale).coerceIn(from, legEnd)
                add(from, cut, ref)
                from = cut; ref = x.ref
            }
            add(from, legEnd, ref)
            at = legEnd
        }
        val out = ArrayList<Shield>()
        for (s in stretches) {
            if (s.ref.isEmpty() || s.toM - s.fromM < MIN_STRETCH_M) continue
            val parsed = parseRouteRef(s.ref)
            // A county road reads by its own number ("E6" for CR E6), as the basemap's shield does.
            val text = if (parsed.type == ShieldType.GENERIC) s.ref.filter { it.isLetterOrDigit() }.let { c -> if (c.length > 2 && c.startsWith("CR", ignoreCase = true)) c.drop(2) else c }
                else parsed.number
            if (text.isEmpty() || text.length > MAX_TEXT) continue
            var d = s.fromM + FIRST_AFTER_M
            while (d <= s.toM - END_CLEAR_M) {
                out += Shield(RouteProjection.pointAt(line, cum, d), parsed.type, text)
                d += EVERY_M
            }
        }
        return out
    }

    /** One road however its number is written ("I 5", "I-5 N"): letters and number only. */
    private fun key(ref: String): String {
        val compact = ref.uppercase().filter { it.isLetterOrDigit() }
        return Regex("^([A-Z]*?)(\\d+)").find(compact)?.let { it.groupValues[1] + it.groupValues[2] } ?: compact
    }
}
