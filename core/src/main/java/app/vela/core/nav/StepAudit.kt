package app.vela.core.nav

import app.vela.core.model.LatLng
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos

/**
 * Do a route's steps describe its line? Each turn is looked up on the line where the step
 * lengths put it (the way NavEngine places it) and the line has to bend that way there; and
 * every sharp bend of the line has to have a step. Built to check a stitched route (Google's
 * line with steps from other sources) on any trip without reading it by hand: a left read out
 * where the line goes right, or a corner with nothing said, shows here. No names, no positions
 * in the summary, so it can be logged.
 */
object StepAudit {
    class Finding(val atM: Double, val what: String)
    class Report(val turns: Int, val agree: Int, val findings: List<Finding>) {
        val otherWay get() = findings.count { it.what.startsWith("other way") }
        val noBend get() = findings.count { it.what.startsWith("no bend") }
        val unsaid get() = findings.count { it.what.startsWith("bend unsaid") }
        fun summary() = "steps vs line: $turns turns, $agree agree, $otherWay the other way, $noBend with no bend, $unsaid bends unsaid"
    }

    private const val NEAR_M = 8.0   // skipped either side of the point: the junction itself
    private const val FAR_M = 40.0   // how far the headings before and after are taken over
    private const val SEARCH_M = 40.0 // a step may sit this far from the bend it is about
    private const val TURN_DEG = 30.0
    private const val SHARP_BEND_DEG = 60.0
    private const val STEP_NEAR_M = 60.0

    private val LEFT = setOf(ManeuverType.TURN_LEFT, ManeuverType.SHARP_LEFT)
    private val RIGHT = setOf(ManeuverType.TURN_RIGHT, ManeuverType.SHARP_RIGHT)

    /** How far the line turns at [at] meters along it, in degrees, positive to the right: the
     *  heading over the 8 to 40 m after against the 8 to 40 m before. */
    fun bendAt(line: List<LatLng>, cum: DoubleArray, at: Double): Double {
        val total = cum.last()
        val a = RouteProjection.pointAt(line, cum, (at - FAR_M).coerceIn(0.0, total))
        val b = RouteProjection.pointAt(line, cum, (at - NEAR_M).coerceIn(0.0, total))
        val c = RouteProjection.pointAt(line, cum, (at + NEAR_M).coerceIn(0.0, total))
        val d = RouteProjection.pointAt(line, cum, (at + FAR_M).coerceIn(0.0, total))
        var x = bearing(c, d) - bearing(a, b)
        while (x > 180) x -= 360
        while (x < -180) x += 360
        return x
    }

    fun check(route: Route): Report {
        val line = route.polyline
        if (line.size < 2) return Report(0, 0, emptyList())
        val cum = RouteProjection.cumulative(line)
        val total = cum.last()
        fun bend(at: Double) = bendAt(line, cum, at)
        val findings = ArrayList<Finding>()
        val stepAt = ArrayList<Double>()
        var at = 0.0
        var turns = 0; var agree = 0
        for (m in route.maneuvers) {
            val here = at
            at += m.distanceMeters
            if (m.type == ManeuverType.DEPART || m.type == ManeuverType.ARRIVE) continue
            stepAt += here
            val wantsRight = when (m.type) { in LEFT -> false; in RIGHT -> true; else -> null } ?: continue
            if (here < FAR_M || here > total - FAR_M) continue // too near an end to take headings
            turns++
            // The bend the step is about: the strongest one its way within reach.
            var best = 0.0
            var o = -SEARCH_M
            while (o <= SEARCH_M) {
                val b = bend(here + o)
                if (if (wantsRight) b > best else b < best) best = b
                o += 10.0
            }
            val here0 = bend(here)
            when {
                abs(best) >= TURN_DEG -> agree++
                abs(here0) >= TURN_DEG -> findings += Finding(here, "other way: ${m.type} where the line bends ${here0.toInt()}")
                else -> findings += Finding(here, "no bend: ${m.type} where the line bends ${here0.toInt()}")
            }
        }
        // Corners of the line nobody mentions.
        var m0 = FAR_M
        var lastFlag = -1e9
        while (m0 <= total - FAR_M) {
            val b = bend(m0)
            if (abs(b) >= SHARP_BEND_DEG && stepAt.none { abs(it - m0) <= STEP_NEAR_M } && m0 - lastFlag > 100.0) {
                findings += Finding(m0, "bend unsaid: the line bends ${b.toInt()} with no step near")
                lastFlag = m0
            }
            m0 += 10.0
        }
        return Report(turns, agree, findings.sortedBy { it.atM })
    }

    private fun bearing(a: LatLng, b: LatLng): Double {
        val dx = (b.lng - a.lng) * cos(Math.toRadians(a.lat))
        val dy = b.lat - a.lat
        return Math.toDegrees(atan2(dx, dy))
    }
}
