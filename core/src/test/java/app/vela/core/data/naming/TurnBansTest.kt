package app.vela.core.data.naming

import app.vela.core.data.RouteGeometry
import app.vela.core.data.ValhallaRouter
import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.model.TrafficSpan
import app.vela.core.model.distanceTo
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class TurnBansTest {
    // A Davis-sized grid: meters east and north of a point, as a line every 10 m.
    private val o = LatLng(38.5449, -121.7405)
    private fun at(e: Double, n: Double) = LatLng(o.lat + n / 110_540.0, o.lng + e / (111_320.0 * Math.cos(Math.toRadians(o.lat))))
    private fun leg(from: Pair<Double, Double>, to: Pair<Double, Double>): List<LatLng> {
        val len = Math.hypot(to.first - from.first, to.second - from.second)
        val n = (len / 10.0).toInt().coerceAtLeast(1)
        return (0..n).map { i -> at(from.first + (to.first - from.first) * i / n, from.second + (to.second - from.second) * i / n) }
    }
    private fun line(vararg pts: Pair<Double, Double>) = pts.toList().zipWithNext().flatMapIndexed { i, (a, b) -> leg(a, b).let { if (i == 0) it else it.drop(1) } }

    @Test fun aJunctionTheOpenRouteLeavesAnotherWayIsAsked() {
        val google = line(0.0 to 0.0, 300.0 to 0.0, 300.0 to 300.0)      // east, then left
        val open = line(0.0 to 0.0, 300.0 to 0.0, 300.0 to -300.0)       // east, then right
        val c = TurnBans.candidates(google, open)
        assertEquals(1, c.size)
        assertEquals(300.0, c[0].atM, 10.0)
        assertTrue(c[0].a.distanceTo(at(260.0, 0.0)) < 12.0)
        assertTrue(c[0].b.distanceTo(at(300.0, 40.0)) < 12.0)
    }

    @Test fun aJunctionTheOpenRoutePassesTwiceIsAskedAboutTheSameApproach() {
        val google = line(0.0 to 0.0, 300.0 to 0.0, 300.0 to 300.0)
        // Right at the junction, round a block to the south, and back north straight through it.
        val open = line(0.0 to 0.0, 300.0 to 0.0, 300.0 to -150.0, 450.0 to -150.0, 450.0 to -250.0, 300.0 to -250.0, 300.0 to 300.0)
        assertEquals(1, TurnBans.candidates(google, open).size)
    }

    @Test fun theSameTurnBothWaysIsNotAsked() {
        val google = line(0.0 to 0.0, 300.0 to 0.0, 300.0 to 300.0)
        assertTrue(TurnBans.candidates(google, google).isEmpty())
    }

    @Test fun aJunctionTheOpenRouteNeverReachesIsNotAsked() {
        val google = line(0.0 to 0.0, 300.0 to 0.0, 300.0 to 300.0)
        val open = line(0.0 to 0.0, 0.0 to 300.0, 300.0 to 300.0)        // north first: never at the corner
        assertTrue(TurnBans.candidates(google, open).isEmpty())
    }

    @Test fun aProbeThatGoesRoundIsABanOnlyWhenItsEndsSnapped() {
        val p = { d: Double, snap: Double -> TurnBans.Probe(emptyList(), d, 60.0, snap) }
        assertEquals(TurnBans.Verdict.LEGAL, TurnBans.verdict(p(95.0, 3.0), 80.0))
        assertEquals(TurnBans.Verdict.ROUND, TurnBans.verdict(p(700.0, 5.0), 80.0))
        assertEquals(TurnBans.Verdict.UNKNOWN, TurnBans.verdict(p(700.0, 20.0), 80.0))
        assertEquals(TurnBans.Verdict.UNKNOWN, TurnBans.verdict(p(170.0, 3.0), 80.0))
        assertEquals(TurnBans.Verdict.UNKNOWN, TurnBans.verdict(null, 80.0))
    }

    @Test fun aSpliceLengthensTheRouteAndMovesTheSpansAfterIt() {
        val g = line(0.0 to 0.0, 600.0 to 0.0)
        val route = Route(
            polyline = g, legs = listOf(RouteLeg(600.0, 60.0, 70.0, emptyList())),
            distanceMeters = 600.0, durationSeconds = 60.0, durationInTrafficSeconds = 70.0,
            trafficSpans = listOf(TrafficSpan(2, 200.0, 100.0), TrafficSpan(3, 250.0, 200.0), TrafficSpan(2, 400.0, 100.0)),
        )
        val round = line(260.0 to 0.0, 260.0 to 100.0, 340.0 to 100.0, 340.0 to 0.0)   // 280 m round a block
        val r = TurnBans.splice(route, 260.0, 340.0, TurnBans.Probe(round, 280.0, 40.0, 2.0))
        assertEquals(800.0, r.distanceMeters, 0.5)
        assertEquals(800.0, r.legs.single().distanceMeters, 0.5)
        assertTrue(r.durationSeconds > 60.0)
        val spans = r.trafficSpans.map { Triple(it.level, it.startMeters.toInt(), it.lengthMeters.toInt()) }
        assertEquals(listOf(Triple(2, 200, 60), Triple(3, 250, 10), Triple(3, 540, 110), Triple(2, 600, 100)), spans)
        // The line goes round the block and carries on east.
        assertTrue(r.polyline.any { it.distanceTo(at(300.0, 100.0)) < 5.0 })
        assertTrue(r.polyline.last().distanceTo(at(600.0, 0.0)) < 1.0)
    }

    /**
     * Both open routers on the real junction of issue #947 (a no-left-turn relation mapped from a
     * survey): the left goes round, and the right, which is allowed, is driven straight through.
     * Skipped without -DvelaLive=true.
     */
    @Test fun issue947BothRoutersGoRoundTheBannedLeft() {
        assumeTrue(System.getProperty("velaLive") == "true")
        val http = OkHttpClient()
        val a = LatLng(48.1009800, 20.7847000)     // 40 m before the junction, heading east
        val left = LatLng(48.1012211, 20.7854122)  // 37 m after it, heading north
        val osrm = RouteGeometry.probeTurn(http, a, 100.0, left, 15.0, 5_000)
        val valhalla = ValhallaRouter.probeTurn(http, a, 100.0, left, 15.0, 5_000)
        assertNotNull(osrm); assertNotNull(valhalla)
        val lineM = a.distanceTo(LatLng(48.1008894, 20.7852807)) + LatLng(48.1008894, 20.7852807).distanceTo(left)
        println("947 left: osrm ${osrm!!.distanceM.toInt()} m snap ${osrm.snapM.toInt()}, valhalla ${valhalla!!.distanceM.toInt()} m snap ${valhalla.snapM.toInt()}, line ${lineM.toInt()} m")
        assertEquals(TurnBans.Verdict.ROUND, TurnBans.verdict(osrm, lineM))
        assertEquals(TurnBans.Verdict.ROUND, TurnBans.verdict(valhalla, lineM))
    }
}
