package app.vela.core.data.transit

import app.vela.core.model.LatLng
import app.vela.core.model.TransitMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Transitous planner reply (a Davis to Sacramento fixture, two Capitol Corridor trains). */
class TransitousPlanTest {
    private val json = javaClass.getResourceAsStream("/transitous/plan-davis-sacramento.json")!!.bufferedReader().readText()
    private val origin = LatLng(38.5449, -121.7405)
    private val dest = LatLng(38.5816, -121.4944)

    @Test fun `itineraries carry their legs, line, stops and times`() {
        val trips = Transitous.parsePlan(json, origin, dest)
        assertEquals(2, trips.size)
        val t = trips[0]
        assertEquals(listOf(TransitMode.WALK, TransitMode.TRAIN, TransitMode.WALK), t.steps.map { it.mode })
        assertEquals("43 min", t.durationText)
        assertEquals("Amtrak", t.agency)
        val ride = t.steps[1]
        assertEquals("534", ride.line!!.name)
        assertEquals("#cae4f1", ride.line!!.colorHex)
        assertEquals("Sacramento", ride.headsign)
        assertEquals("Davis", ride.boardStop!!.name)
        assertEquals("DAV", ride.boardStop!!.code)
        assertNotNull(ride.boardStop!!.timeText)
        assertEquals("Sacramento", ride.alightStop!!.name)
        assertEquals(1, ride.numStops)
        assertEquals(null, ride.delayText)
        // Walk legs carry the endpoints the walk router is asked for later.
        assertEquals(origin, t.steps[0].walkFrom)
        assertTrue(t.steps[0].walkTo!!.lat > 38.54)
        assertEquals(dest, t.steps[2].walkTo)
        assertEquals(listOf("534"), t.lines.map { it.name })
        assertTrue(t.departureEpochSec!! < t.arrivalEpochSec!!)
    }

    @Test fun `duration text folds like the chips expect`() {
        assertEquals("1 min", Transitous.durationText(20))
        assertEquals("45 min", Transitous.durationText(2700))
        assertEquals("1 h", Transitous.durationText(3600))
        assertEquals("1 h 5 min", Transitous.durationText(3900))
    }

    @Test fun `a reply with no itineraries is empty, never a crash`() {
        assertEquals(emptyList<Any>(), Transitous.parsePlan("""{"itineraries":[]}""", origin, dest))
        assertEquals(emptyList<Any>(), Transitous.parsePlan("""{"error":"x"}""", origin, dest))
    }

    @Test fun departAtIsNotArriveBy() {
        val o = app.vela.core.model.LatLng(38.5449, -121.7405); val d = app.vela.core.model.LatLng(38.5816, -121.4944)
        val at = 1_791_275_400L
        val depart = Transitous.planUrl(o, d, 1, at, emptySet())
        org.junit.Assert.assertTrue(depart.contains("&time=")); org.junit.Assert.assertFalse(depart.contains("arriveBy"))
        org.junit.Assert.assertTrue(Transitous.planUrl(o, d, 2, at, emptySet()).contains("arriveBy=true"))
        org.junit.Assert.assertTrue(Transitous.planUrl(o, d, 3, at, emptySet()).contains("arriveBy=true"))
        org.junit.Assert.assertFalse(Transitous.planUrl(o, d, 0, null, emptySet()).contains("time="))
    }

    @Test fun requestsAskForTheAppLanguage() {
        val u = "https://api.transitous.org/api/v1/stoptimes?stopId=x&n=5"
        assertEquals("$u&language=ja", Transitous.withLanguage(u, java.util.Locale.JAPAN))
        assertEquals("$u&language=zh", Transitous.withLanguage(u, java.util.Locale.TRADITIONAL_CHINESE))
        assertEquals("$u&language=he", Transitous.withLanguage(u, java.util.Locale("iw", "IL")))
        assertEquals("$u&language=en", Transitous.withLanguage(u, java.util.Locale.US))
        assertEquals("$u&language=ja", Transitous.withLanguage("$u&language=ja", java.util.Locale.US))
    }

    // Issue #677: a ride's real path, picked out of a plan reply by its two stops.
    private fun leg(mode: String, from: Pair<Double, Double>, to: Pair<Double, Double>, pts: List<Pair<Double, Double>>, mids: Int = 0) =
        """{"mode":"$mode","from":{"name":"a","lat":${from.first},"lon":${from.second}},"to":{"name":"b","lat":${to.first},"lon":${to.second}},""" +
            """"intermediateStops":[${(0 until mids).joinToString(",") { "{}" }}],""" +
            """"legGeometry":{"points":"${app.vela.core.data.google.PolylineCodec.encode(pts.map { app.vela.core.model.LatLng(it.first, it.second) })}","precision":5,"length":${pts.size}}}"""

    private val a = 38.5437 to -121.7377
    private val b = 38.5843 to -121.5004
    private val curve = (0..20).map { (a.first + (b.first - a.first) * it / 20.0 + 0.004 * kotlin.math.sin(it / 20.0 * Math.PI)) to (a.second + (b.second - a.second) * it / 20.0) }

    @Test fun `a ride between the two stops gives its path`() {
        val json = """{"itineraries":[{"legs":[${leg("WALK", a, a, listOf(a, a))},${leg("REGIONAL_RAIL", a, b, curve)}]}]}"""
        val path = Transitous.pickLegShape(json, app.vela.core.model.LatLng(a.first, a.second), app.vela.core.model.LatLng(b.first, b.second))
        assertEquals(21, path?.size)
    }

    @Test fun `a path that is only the stops again is not a shape`() {
        val json = """{"itineraries":[{"legs":[${leg("BUS", a, b, listOf(a, (38.56 to -121.62), b), mids = 1)}]}]}"""
        assertEquals(null, Transitous.pickLegShape(json, app.vela.core.model.LatLng(a.first, a.second), app.vela.core.model.LatLng(b.first, b.second)))
    }

    @Test fun `a ride that starts somewhere else is not this ride`() {
        val far = 38.60 to -121.80
        val json = """{"itineraries":[{"legs":[${leg("BUS", far, b, curve)}]}]}"""
        assertEquals(null, Transitous.pickLegShape(json, app.vela.core.model.LatLng(a.first, a.second), app.vela.core.model.LatLng(b.first, b.second)))
    }
}
