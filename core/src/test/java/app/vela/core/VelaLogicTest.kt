package app.vela.core

import app.vela.core.data.google.PolylineCodec
import app.vela.core.data.google.parse.PhotosParser
import app.vela.core.data.google.parse.SearchParser
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.nav.NavEngine
import app.vela.core.nav.NavEvent
import app.vela.core.nav.NavState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json

class PolylineCodecTest {

    /** Reference vector straight from Google's encoded-polyline documentation. */
    @Test
    fun decodesGoogleReferenceVector() {
        val pts = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-5)
        assertEquals(-120.2, pts[0].lng, 1e-5)
        assertEquals(40.7, pts[1].lat, 1e-5)
        assertEquals(-120.95, pts[1].lng, 1e-5)
        assertEquals(43.252, pts[2].lat, 1e-5)
        assertEquals(-126.453, pts[2].lng, 1e-5)
    }

    @Test
    fun roundTrips() {
        val path = listOf(
            LatLng(37.7749, -122.4194),
            LatLng(37.7849, -122.4094),
            LatLng(37.7949, -122.3994),
        )
        val again = PolylineCodec.decode(PolylineCodec.encode(path))
        assertEquals(path.size, again.size)
        for (i in path.indices) {
            assertEquals(path[i].lat, again[i].lat, 1e-5)
            assertEquals(path[i].lng, again[i].lng, 1e-5)
        }
    }
}

class NavEngineTest {

    private fun straightRoute(): Route {
        val a = LatLng(37.0000, -122.0000)
        val mid = LatLng(37.0050, -122.0000)
        val b = LatLng(37.0100, -122.0000)
        return Route(
            polyline = listOf(a, mid, b),
            legs = listOf(
                RouteLeg(
                    distanceMeters = 1100.0,
                    durationSeconds = 80.0,
                    durationInTrafficSeconds = null,
                    maneuvers = listOf(
                        Maneuver(ManeuverType.DEPART, "Head north", a, 1100.0, 80.0),
                        Maneuver(ManeuverType.ARRIVE, "Arrive", b, 0.0, 0.0),
                    ),
                ),
            ),
            distanceMeters = 1100.0,
            durationSeconds = 80.0,
            durationInTrafficSeconds = null,
        )
    }

    @Test
    fun advancesPastDepartThenArrives() {
        val route = straightRoute()
        val start = route.polyline.first()
        val (afterStart, _) = NavEngine.update(route, NavState(), start)
        assertTrue("should advance off the DEPART step", afterStart.stepIndex >= 1)

        val (arrived, events) = NavEngine.update(route, afterStart, route.polyline.last())
        assertTrue("should mark arrived at destination", arrived.arrived)
        assertTrue("should emit an Arrived event", events.any { it is NavEvent.Arrived })
    }

    @Test
    fun detectsOffRoute() {
        val route = straightRoute()
        // ~400m east of the north-south line → well past the off-route threshold.
        val offPoint = LatLng(37.0050, -121.9955)
        var state = NavState()
        var sawReroute = false
        repeat(6) {
            val (next, events) = NavEngine.update(route, state, offPoint)
            state = next
            if (events.any { it is NavEvent.RerouteNeeded }) sawReroute = true
        }
        assertTrue("repeated off-route fixes should request a reroute", sawReroute)
    }

    @Test
    fun rerouteRequestedOnceNotEveryFix() {
        val route = straightRoute()
        val offPoint = LatLng(37.0050, -121.9955)
        var state = NavState()
        var reroutes = 0
        repeat(8) {
            val (next, events) = NavEngine.update(route, state, offPoint)
            state = next
            reroutes += events.count { it is NavEvent.RerouteNeeded }
        }
        // Edge-triggered: one request on the off-route transition, not one per fix.
        assertEquals(1, reroutes)
    }

    @Test
    fun offRouteClearsWhenBackOnPath() {
        val route = straightRoute()
        val offPoint = LatLng(37.0050, -121.9955)
        var state = NavState()
        repeat(6) { state = NavEngine.update(route, state, offPoint).first }
        assertTrue("should be off-route after repeated off fixes", state.offRoute)

        val (back, _) = NavEngine.update(route, state, LatLng(37.0050, -122.0000)) // on the line
        assertTrue("off-route should clear once back on the path", !back.offRoute)
        assertEquals(0, back.offRouteHits)
    }

    /** Reaching a non-final maneuver advances the step but must NOT mark arrival —
     *  only the final ARRIVE maneuver does. */
    @Test
    fun arrivalRequiresTheFinalManeuver() {
        val a = LatLng(37.0000, -122.0000)
        val mid = LatLng(37.0050, -122.0000)
        val b = LatLng(37.0100, -122.0000)
        val route = Route(
            polyline = listOf(a, mid, b),
            legs = listOf(
                RouteLeg(
                    distanceMeters = 1100.0,
                    durationSeconds = 80.0,
                    durationInTrafficSeconds = null,
                    maneuvers = listOf(
                        Maneuver(ManeuverType.DEPART, "Head north", a, 550.0, 40.0),
                        Maneuver(ManeuverType.TURN_RIGHT, "Turn right", mid, 550.0, 40.0),
                        Maneuver(ManeuverType.ARRIVE, "Arrive", b, 0.0, 0.0),
                    ),
                ),
            ),
            distanceMeters = 1100.0,
            durationSeconds = 80.0,
            durationInTrafficSeconds = null,
        )
        val (s1, _) = NavEngine.update(route, NavState(), a) // off DEPART
        assertTrue(s1.stepIndex >= 1)
        val (s2, e2) = NavEngine.update(route, s1, mid) // at the middle turn
        assertTrue("a non-final maneuver must not arrive", !s2.arrived)
        assertTrue("no Arrived event mid-route", e2.none { it is NavEvent.Arrived })
        val (s3, e3) = NavEngine.update(route, s2, b) // the final point
        assertTrue("the final maneuver arrives", s3.arrived)
        assertTrue(e3.any { it is NavEvent.Arrived })
    }
}

class PhotosParserTest {

    /** The hspqX response is the chunked batchexecute envelope: `)]}'`, a length
     *  line, then the `["wrb.fr","hspqX",<payload-json-string>,…]` row. Photos live
     *  at payload[0][i][6][0]; the FIFE size suffix is normalised. */
    @Test
    fun extractsUserPhotosAndDropsStreetView() {
        // Real anonymous responses interleave Street View thumbnails (no Google
        // login) at the same [6][0] leaf — those must be filtered out, or they
        // render as non-loading placeholders.
        val payload = """[[["pid1",10,12,null,null,null,["https://lh3.googleusercontent.com/abc=w2117-h1000-k-no","",[4608,2176]]],""" +
            """["sv",0,1,null,null,null,["https://streetviewpixels-pa.googleapis.com/v1/thumbnail?panoid=xyz"]],""" +
            """["pid2",10,12,null,null,null,["https://lh3.googleusercontent.com/def=w1776-h1000-k-no"]]],1]"""
        val escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = ")]}'\n\n321\n[[\"wrb.fr\",\"hspqX\",\"$escaped\",null,null,null,\"generic\"],[\"di\",45]]\n"
        val urls = PhotosParser.parse(body)
        assertEquals(2, urls.size) // the Street View entry is dropped
        assertEquals("https://lh3.googleusercontent.com/abc=w1024-h768", urls[0])
        assertEquals("https://lh3.googleusercontent.com/def=w1024-h768", urls[1])
        assertTrue(urls.none { it.contains("streetviewpixels") })
    }

    @Test
    fun returnsEmptyOnGarbage() {
        assertEquals(emptyList<String>(), PhotosParser.parse(""))
        assertEquals(emptyList<String>(), PhotosParser.parse(")]}'\n\n5\n[[\"er\",null]]"))
    }
}

class SearchParserHoursTest {

    private fun json(s: String) = Json.parseToJsonElement(s)

    /** Day-entry shape from the live response — the `[203][0]` and `[118][0][3][0]`
     *  formats share it: day name at `[0]`, hours text at `[3][0][0]`. */
    @Test
    fun parsesWeeklyHours() {
        val days = json(
            """
            [
              ["Tuesday",2,[2026,6,16],[["6 AM–8 PM",[[6],[20]]]],0,1],
              ["Wednesday",3,[2026,6,17],[["7 AM–9 PM",[[7],[21]]]],0,1]
            ]
            """.trimIndent(),
        )
        assertEquals(
            listOf("Tuesday: 6 AM–8 PM", "Wednesday: 7 AM–9 PM"),
            SearchParser.readHours(days),
        )
    }

    @Test
    fun ignoresNonHourArrays() {
        assertEquals(emptyList<String>(), SearchParser.readHours(null))
        assertEquals(emptyList<String>(), SearchParser.readHours(json("[[1,2,3],[4,5,6]]")))
    }

    /** Phase-2: the parser reads every field from the *provided* path map, so a
     *  remote calibration can relocate an index without an app update. Fixture
     *  uses low indices (results at root[0]; name/coords/address shallow) — proving
     *  parse() honours the supplied paths rather than the hard-coded ones. */
    @Test
    fun readsFieldsFromProvidedPaths() {
        val root = json(
            """[ [ [ null, ["Joe's Cafe", [37.5, -122.5], "1 Main St, SF, CA 94101"] ] ] ]""",
        )
        val paths = mapOf(
            "results" to listOf(0),
            "name" to listOf(1, 0),
            "lat" to listOf(1, 1, 0),
            "lng" to listOf(1, 1, 1),
            "address" to listOf(1, 2),
        )
        val places = SearchParser.parse("q", root, null, paths).places
        assertEquals(1, places.size)
        assertEquals("Joe's Cafe", places[0].name)
        assertEquals("1 Main St, SF, CA 94101", places[0].address)
        assertEquals(37.5, places[0].location.lat, 1e-6)
        assertEquals(-122.5, places[0].location.lng, 1e-6)
    }
}
