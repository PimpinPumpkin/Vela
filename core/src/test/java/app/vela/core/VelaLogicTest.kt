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
import app.vela.core.nav.NavReplay
import app.vela.core.nav.NavState
import app.vela.core.replay.TripLog
import app.vela.core.nav.ShieldType
import app.vela.core.nav.parseRouteRef
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

    /** Vela SPEAKS the road name — "turn right onto Larch Way" — not the bare "turn right" modern
     *  Google Maps shortened its voice cue to. We reuse the written instruction (which keeps the
     *  name) for TTS, so the name must survive into the spoken event. */
    @Test
    fun spokenPromptNamesTheRoad() {
        val a = LatLng(37.0000, -122.0000)
        val turn = LatLng(37.0030, -122.0000)   // ~334 m north — inside the 400 m prompt, outside 150 m
        val end = LatLng(37.0030, -121.9960)
        val route = Route(
            polyline = listOf(a, turn, end),
            legs = listOf(
                RouteLeg(
                    distanceMeters = 700.0, durationSeconds = 60.0, durationInTrafficSeconds = null,
                    maneuvers = listOf(
                        Maneuver(ManeuverType.DEPART, "Head north", a, 334.0, 30.0),
                        Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto Larch Way", turn, 366.0, 30.0),
                        Maneuver(ManeuverType.ARRIVE, "Arrive", end, 0.0, 0.0),
                    ),
                ),
            ),
            distanceMeters = 700.0, durationSeconds = 60.0, durationInTrafficSeconds = null,
        )
        val (s1, _) = NavEngine.update(route, NavState(), a, imperial = true) // consume DEPART
        val (_, events) = NavEngine.update(route, s1, a, imperial = true)     // ~334 m out → prompt the turn
        val spoken = events.filterIsInstance<NavEvent.Speak>().map { it.text }
        assertTrue("the spoken prompt must name the road, got $spoken", spoken.any { it.contains("Larch Way") })
    }
}

/** The offline nav auditor ([NavReplay]) — replays a GPS track through the real engine and diffs
 *  the cards/voice against the plotted route's actual maneuver positions. These are the automation
 *  that lets a saved trip be checked without remembering where it went wrong. */
class NavReplayTest {

    /** Walk [poly] into evenly-spaced fixes (~[stepM] apart) that follow it exactly — a clean drive. */
    private fun densify(poly: List<LatLng>, stepM: Double = 15.0): List<LatLng> {
        val out = ArrayList<LatLng>()
        for (i in 0 until poly.size - 1) {
            val a = poly[i]; val b = poly[i + 1]
            val steps = maxOf(1, (a.distanceTo(b) / stepM).toInt())
            for (s in 0 until steps) {
                val t = s.toDouble() / steps
                out += LatLng(a.lat + (b.lat - a.lat) * t, a.lng + (b.lng - a.lng) * t)
            }
        }
        out += poly.last()
        return out
    }

    /** North, then east, then north again — two real turns plus depart/arrive. */
    private fun lRoute(): Route {
        val a = LatLng(37.0000, -122.0000)
        val c1 = LatLng(37.0100, -122.0000)  // ~1.11 km north — turn right
        val c2 = LatLng(37.0100, -121.9900)  // ~0.89 km east — turn left
        val end = LatLng(37.0200, -121.9900) // ~1.11 km north — arrive
        return Route(
            polyline = listOf(a, c1, c2, end),
            legs = listOf(
                RouteLeg(
                    distanceMeters = 3100.0, durationSeconds = 240.0, durationInTrafficSeconds = null,
                    maneuvers = listOf(
                        Maneuver(ManeuverType.DEPART, "Head north", a, 1113.0, 86.0),
                        Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto B Street", c1, 888.0, 70.0),
                        Maneuver(ManeuverType.TURN_LEFT, "Turn left onto C Avenue", c2, 1113.0, 84.0),
                        Maneuver(ManeuverType.ARRIVE, "Arrive at destination", end, 0.0, 0.0),
                    ),
                ),
            ),
            distanceMeters = 3100.0, durationSeconds = 240.0, durationInTrafficSeconds = null,
        )
    }

    @Test
    fun auditsACleanDriveWithNoSuspects() {
        val route = lRoute()
        val report = NavReplay.analyze(route, densify(route.polyline), imperial = true)

        assertTrue("should produce per-fix cards", report.cards.isNotEmpty())
        // Every real turn (not the arrival) gets a spoken pre-turn prompt and a turn-now cue.
        val realTurns = report.maneuvers.filter { it.type != ManeuverType.ARRIVE }
        assertTrue("every turn announced: ${report.summary()}", realTurns.all { it.announced })
        assertTrue("every turn got a turn-now cue", report.maneuvers.dropLast(1).all { it.turnNowFired })
        assertTrue("arrival should fire", report.maneuvers.last().turnNowFired)
        // The card distance tracks reality closely on a clean drive, and nothing is flagged.
        assertTrue("card error should stay small, was ${report.worstCardErrorM}", report.worstCardErrorM < 150.0)
        assertTrue("a clean drive must flag nothing:\n${report.summary()}", report.suspects.isEmpty())
        // The analyzer's MEASUREMENTS must be right — that's what makes it trustworthy on real
        // logs: turns announced ~400 m out (the prompt distance), and the track passing through
        // each turn (nearest ≈ 0). These are the numbers I'll read off a shipped travel log.
        realTurns.filter { it.type != ManeuverType.DEPART }.forEach {
            val ahead = it.firstAnnounceAheadM ?: -1.0
            assertTrue("turn ${it.index} announced $ahead out — expected ~400 m lead", ahead in 250.0..450.0)
            assertTrue("track should pass through turn ${it.index} (nearest ${it.nearestApproachM})", it.nearestApproachM < 20.0)
        }
    }

    /** The diff/flagging logic itself — the heuristics that turn raw measurements into "this turn's
     *  guidance disagreed with the blue line". Tested directly (the robust engine won't emit these
     *  on a synthetic clean route, but a real Google-data glitch can). */
    @Test
    fun flagsTheGuidanceBugsButNotACleanTurn() {
        fun diff(
            type: ManeuverType, announced: Boolean, turnNow: Boolean,
            firstAhead: Double?, cardErr: Double, nearest: Double,
        ) = NavReplay.ManeuverDiff(1, type, "x", 1000.0, announced, turnNow, firstAhead, cardErr, nearest)

        // A normal turn: announced ~400 m out, turn-now fired, card accurate, drove through it.
        val clean = diff(ManeuverType.TURN_RIGHT, announced = true, turnNow = true, firstAhead = 390.0, cardErr = 30.0, nearest = 4.0)
        assertTrue("a clean turn must not be flagged", !clean.suspect)
        assertTrue(clean.flags.isEmpty())

        // Silent exit: drove right past a turn that was never announced (the field "went quiet" bug).
        val silent = diff(ManeuverType.RAMP_RIGHT, announced = false, turnNow = false, firstAhead = null, cardErr = 20.0, nearest = 25.0)
        assertTrue("a silent missed turn must be flagged", silent.suspect)
        assertTrue("…and labelled silent: ${silent.flags}", silent.flags.any { it.contains("SILENT") })

        // Announced miles too early (the "exit in 6 miles that didn't exist yet" bug).
        val early = diff(ManeuverType.RAMP_RIGHT, announced = true, turnNow = true, firstAhead = 9000.0, cardErr = 50.0, nearest = 5.0)
        assertTrue("a too-early announcement must be flagged", early.suspect)
        assertTrue("…and labelled early: ${early.flags}", early.flags.any { it.contains("early") })

        // Card lying about the distance to the next turn.
        val liar = diff(ManeuverType.CONTINUE, announced = true, turnNow = true, firstAhead = 300.0, cardErr = 5000.0, nearest = 5.0)
        assertTrue("a wildly wrong card distance must be flagged", liar.suspect)
        assertTrue("…and labelled as a card error: ${liar.flags}", liar.flags.any { it.contains("card off") })

        // A genuinely missing arrival/depart shouldn't trip the turn-only heuristics.
        val arrive = diff(ManeuverType.ARRIVE, announced = false, turnNow = false, firstAhead = null, cardErr = 40.0, nearest = 10.0)
        assertTrue("ARRIVE/DEPART are exempt from the silent-turn rule", !arrive.suspect)
    }

    /** The whole shipped-log pipeline: encode a route + fixes in the exact on-device CSV format,
     *  parse it back, and audit it — so a real travel log can be dropped in and analysed in one
     *  call. Guards the TripStore ↔ TripLog format contract too. */
    @Test
    fun roundTripsAndAuditsASavedTripCsv() {
        val route = lRoute()
        val fixes = densify(route.polyline)
        val csv = buildString {
            val dst = route.polyline.last()
            append("META,Test Drive,1700000000000,${dst.lat},${dst.lng}\n")
            append(TripLog.encodeRoute(route)) // the same block TripStore.saveRoute writes
            for (f in fixes) append("${f.lat},${f.lng},0,0.0,12.0\n")
        }

        val parsed = TripLog.parse(csv)
        assertEquals("label round-trips", "Test Drive", parsed.label)
        assertEquals("polyline round-trips", route.polyline.size, parsed.route?.polyline?.size)
        assertEquals("maneuvers round-trip", route.maneuvers.size, parsed.route?.maneuvers?.size)
        assertEquals("every GPS fix parsed (route lines skipped)", fixes.size, parsed.points.size)

        val report = TripLog.audit(csv)
            ?: throw AssertionError("audit returned null — the saved route didn't parse")
        assertTrue("end-to-end audit of a clean saved trip flags nothing:\n${report.summary()}", report.suspects.isEmpty())
    }

    /**
     * On-demand harness for a REAL shared travel log. Point it at a trip CSV exported from the
     * phone and it prints the per-maneuver audit plus the spoken-line timeline (where along the
     * route each voice cue fired) — the concrete "how the cards/voice differ from the blue line".
     *
     *   ./gradlew :core:testDebugUnitTest --tests '*auditSharedTripLog' -DvelaTrip=/abs/path/trip.csv --info
     *
     * Skipped (not failed) when `-DvelaTrip` is unset, so normal/CI runs ignore it.
     */
    @Test
    fun auditSharedTripLog() {
        val path = System.getProperty("velaTrip")
        org.junit.Assume.assumeTrue("set -DvelaTrip=<csv> to audit a real travel log", !path.isNullOrBlank())
        val csv = java.io.File(path!!).readText()
        val report = TripLog.audit(csv)
        if (report == null) {
            println("[NavReplay] $path has no saved route — replay re-routes; nothing to diff against")
            return
        }
        println("[NavReplay] auditing $path\n${report.summary()}")
        println("[NavReplay] spoken-line timeline (fix @ metres-along-route):")
        report.cards.filter { it.spoke.isNotEmpty() }.forEach { c ->
            println("  @${c.fixIndex} (${c.alongM.toInt()} m): ${c.spoke.joinToString(" | ")}")
        }
    }
}

/** Maneuver parsing pinned to the REAL keyless step markup (captured live from
 *  www.google.com/maps/preview/directions): a generic `maneuver='TURN'` token with the direction
 *  in a child `<turn side= type=>`. The old mapType only knew `TURN_LEFT`-style tokens, so every
 *  plain turn/ramp fell through to UNKNOWN — generic arrow + wrong haptic. */
class DirectionsManeuverTest {

    @Test
    fun mapsRealKeylessTurnTokensAndKeepsRoadName() {
        val left = DirectionsParser.parseStep(
            "<step maneuver='TURN' meters='420'>Turn <turn side='LEFT'>left</turn> onto " +
                "<roadlist><road lang='en'>124th Ave NE</road></roadlist></step>",
        )
        assertEquals("a plain TURN must resolve its side, not fall to UNKNOWN", ManeuverType.TURN_LEFT, left.type)
        assertEquals("Turn left onto 124th Ave NE", left.instruction)

        val ramp = DirectionsParser.parseStep(
            "<step maneuver='ON_RAMP' meters='2246'>Slight <turn side='RIGHT' type='SLIGHT'>right</turn> " +
                "onto the ramp to <signlist><sign lang='en'>Arlington</sign></signlist></step>",
        )
        assertEquals(ManeuverType.RAMP_RIGHT, ramp.type)
        assertTrue("ramp instruction kept", ramp.instruction.contains("ramp to Arlington"))

        val roundabout = DirectionsParser.parseStep(
            "<step maneuver='ROUNDABOUT_ENTER_AND_EXIT' meters='4557'>At the traffic circle, take the " +
                "<exit number='SECOND'>2nd</exit> exit</step>",
        )
        assertEquals(ManeuverType.ROUNDABOUT, roundabout.type)
        assertEquals("At the traffic circle, take the 2nd exit", roundabout.instruction)

        // sharp + slight variants resolve too
        assertEquals(
            ManeuverType.SLIGHT_RIGHT,
            DirectionsParser.parseStep("<step maneuver='TURN' meters='5'>Slight <turn side='RIGHT' type='SLIGHT'>right</turn></step>").type,
        )
        assertEquals(
            ManeuverType.TURN_RIGHT,
            DirectionsParser.parseStep("<step maneuver='TURN' meters='5'>Turn <turn side='RIGHT'>right</turn></step>").type,
        )
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

class RouteRefTest {
    @Test
    fun classifiesRouteRefsByPrefix() {
        val i = parseRouteRef("I-80 E")
        assertEquals(ShieldType.INTERSTATE, i.type)
        assertEquals("80", i.number)
        assertEquals("E", i.direction)
        assertEquals(ShieldType.US_ROUTE, parseRouteRef("US 50").type)
        assertEquals(ShieldType.US_ROUTE, parseRouteRef("US-101 N").type)
        assertEquals(ShieldType.STATE, parseRouteRef("CA-99").type)
        assertEquals(ShieldType.STATE, parseRouteRef("ON-401").type) // Canada province
        assertEquals(ShieldType.STATE, parseRouteRef("SR 1").type)
        assertEquals(ShieldType.GENERIC, parseRouteRef("ZZ-9").type) // unknown 2-letter → plain chip
    }
}
