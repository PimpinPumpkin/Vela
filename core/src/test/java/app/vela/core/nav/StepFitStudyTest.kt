package app.vela.core.nav

import app.vela.core.model.ManeuverType
import app.vela.core.replay.TripLog
import org.junit.Test
import java.io.File

/**
 * Recorded trips through [StepFit], offline: `-DvelaTrips=<folder of trip files>`. Prints, per
 * route block, its turns, how many sat off their bend before and after, and what changed, as
 * counts and step types only: no name, no position (trip files hold both).
 */
class StepFitStudyTest {
    @Test fun tripsThroughStepFit() {
        val dir = System.getProperty("velaTrips") ?: return
        val files = File(dir).walk().filter { it.isFile && it.name.endsWith(".csv") }.sortedBy { it.name }.toList()
        var blocks = 0; var changed = 0; var off0 = 0; var off1 = 0; var turns = 0
        files.forEachIndexed { fi, f ->
            TripLog.parse(f.readText()).segments.forEachIndexed { bi, seg ->
                val r = seg.route
                val res = StepFit.fit(r)
                val a = StepFit.turnsOffBend(r); val b = StepFit.turnsOffBend(res.route)
                val t = r.maneuvers.count { it.type in setOf(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT) }
                blocks++; turns += t; off0 += a; off1 += b; if (res.changed) changed++
                fun seq(x: app.vela.core.model.Route) = x.maneuvers.joinToString(" ") { m -> m.type.name.replace("TURN_", "").replace("SHARP_", "S").take(5) + if (m.road.isNullOrBlank() && m.ref.isNullOrBlank()) "" else "*" }
                println("trip ${fi + 1} block ${bi + 1}: ${seg.reason ?: "?"} turns $t, off their bend $a -> $b${if (res.changed) ", ${res.note}" else ""}")
                if (res.changed) { println("    before: ${seq(r)}"); println("    after:  ${seq(res.route)}") }
            }
        }
        println("TOTAL: ${files.size} trips, $blocks blocks, $changed changed, turns $turns, off their bend $off0 -> $off1")
    }
}
