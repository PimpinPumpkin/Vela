package app.vela.core

import app.vela.core.data.google.parse.DirectionsParser
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
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

    /** Typical best→worst spread: summary[10][4] = [lowSeconds, highSeconds, label].
     *  Google's own depart-time planning hint; also confirms the live-traffic duration
     *  reads from summary[10][0][0]. Calibrated 2026-06-20 (Davis→SF). */
    @Test fun parsesTypicalRangeFromSummary10() {
        val traffic = arr(5, 0 to "[4290,\"now\"]", 4 to "[4096,5239,\"1 hr 8 min to 1 hr 27 min\"]")
        val summary = arr(11, 2 to "[10000]", 3 to "[4670]", 10 to traffic)
        val route = arr(8, 0 to summary)
        val node0 = arr(2, 1 to "[$route]")
        val routes = DirectionsParser.parse(Json.parseToJsonElement("[$node0]"))
        assertEquals(1, routes.size)
        assertEquals(4290.0, routes[0].durationInTrafficSeconds!!, 1e-9)
        assertEquals(4096.0, routes[0].typicalLowSeconds!!, 1e-9)
        assertEquals(5239.0, routes[0].typicalHighSeconds!!, 1e-9)
        val range = routes[0].typicalRangeSeconds!!
        assertEquals(4096.0, range.first, 1e-9)
        assertEquals(5239.0, range.second, 1e-9)
    }

    @Test fun typicalRangeAbsentWhenNoNode() {
        val r = DirectionsParser.parse(Json.parseToJsonElement(root(null)))[0]
        assertEquals(null, r.typicalLowSeconds)
        assertEquals(null, r.typicalRangeSeconds)
    }

    /** A degenerate (zero-width) range collapses to null so the UI doesn't show
     *  "usually 1 hr 10 min – 1 hr 10 min". */
    /** The ARRIVE maneuver MUST sit at the route's end (the destination). Google's step
     *  distances can total well short of the geometry length, so the cumulative fraction
     *  undershoots — which once placed "arrive" ~15 km early and fired the arrival trigger
     *  there (a real test-drive bug). The last maneuver is pinned to the end. */
    @Test fun arriveManeuverPinnedToRouteEnd() {
        val poly = listOf(LatLng(0.0, 0.0), LatLng(0.0, 1.0)) // ~111 km
        val mans = listOf(
            Maneuver(ManeuverType.DEPART, "Start", LatLng(0.0, 0.0), 0.0, 0.0),
            Maneuver(ManeuverType.STRAIGHT, "Continue", LatLng(0.0, 0.0), 10_000.0, 0.0), // 10 km of steps
            Maneuver(ManeuverType.ARRIVE, "Arrive", LatLng(0.0, 0.0), 0.0, 0.0),
        )
        val placed = DirectionsParser.placeManeuvers(mans, poly)
        assertEquals(0.0, placed.last().location.lat, 1e-6)
        assertEquals(1.0, placed.last().location.lng, 1e-6) // route end, not ~0.09 along
    }

    @Test fun degenerateRangeIsNull() {
        val traffic = arr(5, 0 to "[4200,\"now\"]", 4 to "[4200,4200,\"x\"]")
        val summary = arr(11, 2 to "[10000]", 3 to "[4200]", 10 to traffic)
        val route = arr(8, 0 to summary)
        val r = DirectionsParser.parse(Json.parseToJsonElement("[${arr(2, 1 to "[$route]")}]"))[0]
        assertEquals(null, r.typicalRangeSeconds)
    }
}
