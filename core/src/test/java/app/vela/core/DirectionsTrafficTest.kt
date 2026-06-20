package app.vela.core

import app.vela.core.data.google.parse.DirectionsParser
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/** The per-segment live-traffic spans at `route[3][5][0]` — `[level, startMeters,
 *  lengthMeters]` tuples — drive the route line's Google-style congestion colour.
 *  Calibrated 2026-06-19; these guard the index path + tuple order. */
class DirectionsTrafficTest {

    /** JSON array of [size] nulls with index → raw-JSON overrides. */
    private fun arr(size: Int, vararg at: Pair<Int, String>): String {
        val m = at.toMap()
        return (0 until size).joinToString(",", "[", "]") { m[it] ?: "null" }
    }

    /** A minimal route: summary distance `[0][2][0]` + typical duration `[0][3][0]`
     *  (both required by the parser) and the traffic block at `[3][5][0]`. */
    private fun root(trafficBlock: String?): String {
        val summary = arr(11, 2 to "[10000]", 3 to "[600]")
        val route = if (trafficBlock == null) arr(8, 0 to summary)
        else arr(8, 0 to summary, 3 to trafficBlock)
        val node0 = arr(2, 1 to "[$route]") // root[0][1] = [route]
        return "[$node0]"
    }

    @Test fun parsesSpansInLevelStartLengthOrder() {
        val block = arr(6, 5 to "[[[1,2000,500],[2,5000,800]]]") // [5][0] = list of spans
        val routes = DirectionsParser.parse(Json.parseToJsonElement(root(block)))
        assertEquals(1, routes.size)
        val spans = routes[0].trafficSpans
        assertEquals(2, spans.size)
        assertEquals(1, spans[0].level)
        assertEquals(2000.0, spans[0].startMeters, 1e-9)
        assertEquals(500.0, spans[0].lengthMeters, 1e-9)
        assertEquals(2, spans[1].level)
        assertEquals(5000.0, spans[1].startMeters, 1e-9)
        assertEquals(800.0, spans[1].lengthMeters, 1e-9)
    }

    @Test fun zeroLengthSpansAreDropped() {
        val block = arr(6, 5 to "[[[1,2000,0],[2,5000,800]]]")
        val spans = DirectionsParser.parse(Json.parseToJsonElement(root(block)))[0].trafficSpans
        assertEquals(1, spans.size)
        assertEquals(5000.0, spans[0].startMeters, 1e-9)
    }

    @Test fun noTrafficBlockYieldsEmptySpansNotCrash() {
        val routes = DirectionsParser.parse(Json.parseToJsonElement(root(null)))
        assertEquals(1, routes.size)
        assertEquals(0, routes[0].trafficSpans.size)
    }
}
