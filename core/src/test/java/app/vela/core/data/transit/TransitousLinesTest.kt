package app.vela.core.data.transit

import app.vela.core.data.google.PolylineCodec
import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The map/routes reply, in the shape captured from the service on 2026-10-02. */
class TransitousLinesTest {
    private fun enc(vararg p: LatLng) = PolylineCodec.encode(p.toList()).replace("\\", "\\\\")
    // PolylineCodec.encode writes precision 5.
    private val track = enc(LatLng(38.5449, -121.7405), LatLng(38.5460, -121.7390))
    private val reply = """
      {"routes":[
        {"mode":"SUBWAY","transitRoutes":[{"id":"M","shortName":"M","color":"EB6800","textColor":"ffffff"}],"segments":[]},
        {"mode":"SUBWAY","transitRoutes":[{"id":"J","shortName":"J","color":"8e5c33"}],"segments":[]},
        {"mode":"BUS","transitRoutes":[{"id":"42","shortName":"42","color":"0000ff"}],"segments":[]},
        {"mode":"REGIONAL_RAIL","transitRoutes":[{"id":"R","shortName":"R","color":""}],"segments":[]}
      ],
      "polylines":[
        {"polyline":{"points":"$track","precision":5,"length":2},"colors":["eb6800","8e5c33"],"routeIndexes":[0,1]},
        {"polyline":{"points":"$track","precision":5,"length":2},"colors":["0000ff"],"routeIndexes":[2]},
        {"polyline":{"points":"$track","precision":5,"length":2},"colors":[],"routeIndexes":[3]}
      ],"stops":[],"zoomFiltered":false}
    """.trimIndent()

    @Test fun railKeepsItsLinesColorsAndBusesAreDropped() {
        val lines = Transitous.parseLines(reply)
        assertEquals(2, lines.size)
        assertEquals(listOf("#eb6800", "#8e5c33"), lines[0].colors)
        assertEquals(Transitous.Kind.METRO, lines[0].kind)
        assertEquals("a train with no color of its own", emptyList<String>(), lines[1].colors)
        assertEquals(Transitous.Kind.TRAIN, lines[1].kind)
        assertEquals(2, lines[0].points.size)
    }

    @Test fun aChangedReplyIsNoLines() {
        assertTrue(Transitous.parseLines("{\"error\":\"moved\"}").isEmpty())
        assertTrue(Transitous.parseLines("not json").isEmpty())
    }

    @Test fun modesFallIntoThreeKinds() {
        assertEquals(Transitous.Kind.METRO, Transitous.kindOf("TRAM"))
        assertEquals(Transitous.Kind.TRAIN, Transitous.kindOf("HIGHSPEED_RAIL"))
        assertEquals(Transitous.Kind.BUS, Transitous.kindOf("COACH"))
        assertEquals(null, Transitous.kindOf("FERRY"))
    }

    @Test fun aStraightRunThinsToItsEnds() {
        val pts = (0..20).map { LatLng(38.5449 + it * 0.0001, -121.7405) }
        assertEquals(2, Transitous.simplify(pts, 4.0).size)
    }
}
