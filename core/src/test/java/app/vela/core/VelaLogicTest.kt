package app.vela.core

import app.vela.core.data.google.PolylineCodec
import app.vela.core.data.google.parse.DirectionsParser
import app.vela.core.data.google.parse.PhotosParser
import app.vela.core.data.google.parse.SearchParser
import app.vela.core.data.google.parse.TransitParser
import app.vela.core.model.Photo
import app.vela.core.model.TransitMode
import app.vela.core.model.LatLng
import app.vela.core.model.distanceTo
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

    /** A route that passes near itself: up the west line, a tiny hop east, back down a
     *  parallel east line ~5 m away (an out-and-back / switchback). A naïve global-nearest
     *  "remaining" matches the return leg and collapses to almost-arrived while you're still
     *  on the way out. Forward progress must keep it honest. Regression for the test-drive's
     *  "51 mi to turn · 0.3 mi remaining". */
    private fun hairpinRoute(): Route {
        val a = LatLng(37.0000, -122.00000)
        val t = LatLng(37.0100, -122.00000)   // top of the outbound (west) leg
        val t2 = LatLng(37.0100, -121.99994)  // hop ~5 m east
        val m2 = LatLng(37.0050, -121.99994)  // mid inbound (east) leg — ~5 m from the outbound midpoint
        val b = LatLng(37.0000, -121.99994)   // end, beside the start
        return Route(
            polyline = listOf(a, t, t2, m2, b),
            legs = listOf(
                RouteLeg(
                    distanceMeters = 2232.0,
                    durationSeconds = 200.0,
                    durationInTrafficSeconds = null,
                    maneuvers = listOf(
                        Maneuver(ManeuverType.DEPART, "Head north", a, 0.0, 0.0),
                        Maneuver(ManeuverType.UTURN, "Make a U-turn", t, 1113.0, 100.0),
                        Maneuver(ManeuverType.ARRIVE, "Arrive", b, 1119.0, 100.0),
                    ),
                ),
            ),
            distanceMeters = 2232.0,
            durationSeconds = 200.0,
            durationInTrafficSeconds = null,
        )
    }

    @Test
    fun remainingStaysHonestWhenRoutePassesNearItself() {
        val route = hairpinRoute()
        // Drive up the outbound (west) leg: start, ~quarter, ~halfway.
        var state = NavEngine.update(route, NavState(), LatLng(37.0000, -122.0000)).first
        state = NavEngine.update(route, state, LatLng(37.0030, -122.0000)).first
        val remAtQuarter = state.remainingDistance
        state = NavEngine.update(route, state, LatLng(37.0050, -122.0000)).first // ~5 m from the inbound leg

        // Global-nearest would snap onto the return leg here and report ~560 m (almost
        // arrived). Forward progress keeps it honest: ~556 m covered of ~2232 m → ~1675 left.
        assertTrue(
            "remaining must not collapse onto the nearby return leg (was ${state.remainingDistance})",
            state.remainingDistance > 1400.0,
        )
        // The contradiction the user saw — next turn farther than the whole trip — must not happen.
        assertTrue(
            "next-turn (${state.distanceToNextManeuver}) can't exceed remaining (${state.remainingDistance})",
            state.distanceToNextManeuver <= state.remainingDistance + 1.0,
        )
        // Progress is monotonic and advancing.
        assertTrue("remaining should shrink as we drive", state.remainingDistance < remAtQuarter)
        assertTrue("traveled progress should advance", state.traveledM in 450.0..700.0)
    }

    // --- regression tests for the 2026-06-27 highway-drive bugs (turns 6 mi out of sync) ----

    /** A turn must sit at the START of its step, not the end. The off-by-one (adding the step
     *  length BEFORE placing) put every turn a whole step too far — a 9 km highway step landed
     *  the exit 9 km past where it actually is. */
    @Test
    fun maneuverSitsAtStartOfItsStepNotEnd() {
        val a = LatLng(37.0000, -122.0000)
        val b = LatLng(37.0900, -122.0000) // ~10 km straight north
        val maneuvers = listOf(
            Maneuver(ManeuverType.DEPART, "Head north", LatLng(0.0, 0.0), 1000.0, 0.0),
            Maneuver(ManeuverType.RAMP_RIGHT, "Take the exit", LatLng(0.0, 0.0), 9000.0, 0.0),
            Maneuver(ManeuverType.ARRIVE, "Arrive", LatLng(0.0, 0.0), 0.0, 0.0),
        )
        val exit = DirectionsParser.placeManeuvers(maneuvers, listOf(a, b))[1].location
        // The exit opens its 9 km step → ~1 km from the start, NOT ~10 km (parked at the end).
        assertTrue("exit must be ~1 km from start, was ${exit.distanceTo(a)} m", exit.distanceTo(a) < 2000.0)
        assertTrue("exit must NOT be parked at the route end", exit.distanceTo(b) > 5000.0)
    }

    /** A maneuver that's geographically NEAR an early point but FAR along the route (a highway
     *  curving back near an exit) must not be announced/advanced early — prompts + advancement
     *  measure ALONG the route, not crow-flies. */
    @Test
    fun doesNotSkipAManeuverNearByCrowFliesButFarAlongTheRoute() {
        val a = LatLng(37.0000, -122.0000)
        val far = LatLng(37.0500, -122.0000)   // ~5.5 km north
        val back = LatLng(37.0000, -122.0001)  // ~9 m WEST of A, but ~11 km along the route
        val end = LatLng(37.0000, -122.0050)
        val route = Route(
            polyline = listOf(a, far, back, end),
            legs = listOf(
                RouteLeg(
                    distanceMeters = 11000.0, durationSeconds = 600.0, durationInTrafficSeconds = null,
                    maneuvers = listOf(
                        Maneuver(ManeuverType.DEPART, "Head north", a, 11000.0, 600.0),
                        Maneuver(ManeuverType.TURN_RIGHT, "Turn right", back, 400.0, 30.0),
                        Maneuver(ManeuverType.ARRIVE, "Arrive", end, 0.0, 0.0),
                    ),
                ),
            ),
            distanceMeters = 11000.0, durationSeconds = 600.0, durationInTrafficSeconds = null,
        )
        val (s1, _) = NavEngine.update(route, NavState(), a)  // consume DEPART
        assertEquals("should be targeting the turn", 1, s1.stepIndex)
        val (s2, _) = NavEngine.update(route, s1, a)          // still at the start
        assertEquals("must NOT skip the loop-back turn that's only ~9 m away crow-flies", 1, s2.stepIndex)
    }

    /** Spoken prompt distances follow the Imperial setting (TTS used to always say metres). */
    @Test
    fun spokenPromptsUseImperialWhenImperial() {
        val (_, events) = NavEngine.update(straightRoute(), NavState(), straightRoute().polyline.first(), imperial = true)
        val spoken = events.filterIsInstance<NavEvent.Speak>().map { it.text }
        assertTrue("a prompt should use feet/miles, got $spoken", spoken.any { it.contains("feet") || it.contains("mile") })
        assertTrue("must not say metres in imperial mode, got $spoken", spoken.none { it.contains("meter") })
    }

    @Test
    fun spokenPromptsUseMetresWhenMetric() {
        val (_, events) = NavEngine.update(straightRoute(), NavState(), straightRoute().polyline.first(), imperial = false)
        val spoken = events.filterIsInstance<NavEvent.Speak>().map { it.text }
        assertTrue("a prompt should use metres, got $spoken", spoken.any { it.contains("meter") })
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
        val photos = PhotosParser.parse(body)
        assertEquals(2, photos.size) // the Street View entry is dropped
        assertEquals("https://lh3.googleusercontent.com/abc=w1024-h768", photos[0].url)
        assertEquals("https://lh3.googleusercontent.com/def=w1024-h768", photos[1].url)
        assertTrue(photos.none { it.url.contains("streetviewpixels") })
        assertEquals(null, photos[0].postedText) // no [21][6][8] in this payload
    }

    /** Posted date from `entry[21][6][8]` = `[year, month, day, hour]` → "May 2026". */
    @Test
    fun readsPostedDate() {
        val entry = (0..21).joinToString(",", "[", "]") { i ->
            when (i) {
                0 -> "\"pid\""
                6 -> "[\"https://lh3.googleusercontent.com/xyz=w800-h600-k-no\"]"
                21 -> "[null,null,null,null,null,null,[null,null,null,null,null,null,null,null,[2026,5,25,14]]]"
                else -> "null"
            }
        }
        val payload = "[[$entry],1]"
        val escaped = payload.replace("\\", "\\\\").replace("\"", "\\\"")
        val body = ")]}'\n\n9\n[[\"wrb.fr\",\"hspqX\",\"$escaped\",null,null,null,\"generic\"]]\n"
        val photos = PhotosParser.parse(body)
        assertEquals(1, photos.size)
        assertEquals("May 2026", photos[0].postedText)
    }

    @Test
    fun returnsEmptyOnGarbage() {
        assertEquals(emptyList<Photo>(), PhotosParser.parse(""))
        assertEquals(emptyList<Photo>(), PhotosParser.parse(")]}'\n\n5\n[[\"er\",null]]"))
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

    /** Price level derived from Google's dollar-range / symbol label (the response
     *  never ships the classic 1–4), powering the price filter. */
    @Test
    fun derivesPriceLevelFromLabel() {
        assertEquals(1, SearchParser.priceLevelOf("$1–10"))
        assertEquals(2, SearchParser.priceLevelOf("$10–20"))
        assertEquals(3, SearchParser.priceLevelOf("$20–30"))
        assertEquals(4, SearchParser.priceLevelOf("$50+"))
        assertEquals(2, SearchParser.priceLevelOf("$$"))   // symbol style
        assertEquals(4, SearchParser.priceLevelOf("$$$$"))
        assertEquals(null, SearchParser.priceLevelOf(null))
        assertEquals(null, SearchParser.priceLevelOf(""))
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

class TransitParserTest {

    // Faithful to a live Davis→Sacramento `!3e3` capture (2026-06-18): trips at
    // root[0][1], each trip is [summary, …] so the summary is trip[0]; within the
    // summary distance [2][1], duration [3][1], depart/arrive [5][0]/[5][1] =
    // [epochSec, tz, "h:mm AM"], agency [6][4][0][0], and line nodes ["<name>", n,
    // "#fill", "#text"] interleaved with mode-icon facets in [14]. The Amtrak
    // trip's badge carries a "bus2.png"/"Bus" facet → mode BUS.
    private val itin0 = """[null,null,[null,"15.0 miles"],[null,"45 min"],null,""" +
        """[[1781788200,"America/Los_Angeles","6:10 AM"],[1781790924,"America/Los_Angeles","6:55 AM"]],""" +
        """[null,null,null,null,[["Amtrak Chartered Vehicle"]]],null,null,null,null,null,null,null,""" +
        """[[4,null,[3,"bus2.png",null,"Bus"]],[5,["Amtrak Thruway Connecting Service",1,"#cae4f1","#000000"]]]]"""
    private val itin1 = """[null,null,[null,"3.2 miles"],[null,"1 hr 8 min"],null,""" +
        """[[1781786400,"America/Los_Angeles","5:40 AM"],[1781790480,"America/Los_Angeles","6:48 AM"]],""" +
        """null,null,null,null,null,null,null,null,""" +
        """[[4,null,[3,"bus2.png",null,"Bus"]],[5,["Route 42B",1,"#0b8043","#ffffff"]]]]"""

    @Test
    fun parsesTransitItineraries() {
        // each trip wraps its summary at [0] — root[0][1] = [[summary0],[summary1]]
        val list = TransitParser.parse(Json.parseToJsonElement("[[null,[[$itin0],[$itin1]]]]"))
        assertEquals(2, list.size)
        val a = list[0]
        assertEquals("45 min", a.durationText)
        assertEquals("15.0 miles", a.distanceText)
        assertEquals("6:10 AM", a.departureText)
        assertEquals("6:55 AM", a.arrivalText)
        assertEquals(1781788200L, a.departureEpochSec)
        assertEquals(1781790924L, a.arrivalEpochSec)
        assertEquals("Amtrak Chartered Vehicle", a.agency)
        assertEquals(1, a.lines.size)
        assertEquals("Amtrak Thruway Connecting Service", a.lines[0].name)
        assertEquals("#cae4f1", a.lines[0].colorHex)
        assertEquals("#000000", a.lines[0].textColorHex)
        assertEquals(TransitMode.BUS, a.lines[0].mode)
        // second itinerary's line is read independently
        assertEquals("Route 42B", list[1].lines[0].name)
        assertEquals("#0b8043", list[1].lines[0].colorHex)
    }

    @Test(expected = app.vela.core.data.CalibrationNeededException::class)
    fun throwsWhenShapeMissing() {
        TransitParser.parse(Json.parseToJsonElement("[[1,2,3]]"))
    }

    // Drill-down legs live at trip[1][0][1] in the same payload: each leg's
    // summary is leg[0] (dur [3][1], dist [2][1], mode/line badge [14]); board/
    // alight times are the first/last "h:mm AM" strings anywhere in the leg.
    private val walkLeg = """[[null,null,[null,"0.3 mi"],[null,"7 min"],null,null,null,null,null,null,null,null,null,null,""" +
        """[[4,null,[3,"walk.png",null,"Walk"]]]]]"""
    private val busLeg = """[[null,null,null,[null,"53 min"],null,null,null,null,null,null,null,null,null,null,""" +
        """[[4,null,[3,"bus2.png",null,"Bus"]],[5,["42B",1,"#008751","#ffffff"]]]],""" +
        """null,null,null,null,[["Fifth St & G St","5:48 AM"],["W. Capitol","6:41 AM"]]]"""

    @Test
    fun parsesTransitStepsFromSamePayload() {
        val legs = "[[null,[$walkLeg,$busLeg]]]" // = trip[1]; trip[1][0][1] is the leg list
        val list = TransitParser.parse(Json.parseToJsonElement("[[null,[[$itin0,$legs]]]]"))
        assertEquals(1, list.size)
        val steps = list[0].steps
        assertEquals(2, steps.size)
        // leg 0 — a walk: no line, no board/alight time, but a duration + distance
        assertEquals(TransitMode.WALK, steps[0].mode)
        assertEquals("7 min", steps[0].durationText)
        assertEquals("0.3 mi", steps[0].distanceText)
        assertEquals(null, steps[0].line)
        assertEquals(null, steps[0].departText)
        // leg 1 — the ride: line, colour, board + alight times
        assertEquals(TransitMode.BUS, steps[1].mode)
        assertEquals("53 min", steps[1].durationText)
        assertEquals("42B", steps[1].line?.name)
        assertEquals("#008751", steps[1].line?.colorHex)
        assertEquals("5:48 AM", steps[1].departText)
        assertEquals("6:41 AM", steps[1].arriveText)
    }
}
