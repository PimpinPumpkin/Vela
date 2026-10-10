package app.vela.core.data.google.parse

import app.vela.core.config.Calibration
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The directions response indices are remote-calibratable (2026-09-13): a moved field is a
 * `directionsPaths` edit in calibration.json, not an app release. Pinned here with a synthetic
 * response in the compiled shape, then the same response with the in-traffic figure moved.
 */
class DirectionsPathsTest {
    // root[0][1] = routes; route[0] = summary; summary[2][0] distance, [3][0] typical,
    // [10][0][0] in-traffic, [7][3][2..3] start, [1] summary text.
    private fun summary(trafficAt10: Boolean, trafficAt11: Boolean) = buildString {
        append("[null,\"via I-80\",[1000.0,\"1 km\"],[600.0,\"10 min\"],null,null,null,")
        append("[null,null,null,[null,null,38.5,-121.7]],null,null,")
        append(if (trafficAt10) "[[720.0,\"12 min\"]]" else "null")
        append(",")
        append(if (trafficAt11) "[720.0]" else "null")
        append("]")
    }
    private fun root(s: String) = Json.parseToJsonElement("[[null,[[$s]]]]")

    // A leg of a trip through stops: route[1][j][0] in the summary's shape.
    private fun leg(distance: Double, typical: Double, traffic: Double?) = buildString {
        append("[null,null,[$distance,\"x\"],[$typical,\"y\"],null,null,null,null,null,null,")
        append(if (traffic != null) "[[$traffic,\"z\"]]" else "null")
        append("]")
    }
    private fun rootWithLegs(s: String, legs: List<String>) =
        Json.parseToJsonElement("[[null,[[$s,[${legs.joinToString(",") { "[$it]" }}]]]]]")

    @Test fun `a trip through a stop keeps each leg's own figures`() {
        val s = summary(trafficAt10 = true, trafficAt11 = false)
        val r = DirectionsParser.parse(rootWithLegs(s, listOf(leg(400.0, 200.0, 300.0), leg(600.0, 400.0, 420.0)))).single()
        assertEquals(2, r.legTimes.size)
        assertEquals(400.0, r.legTimes[0].distanceMeters, 0.0)
        assertEquals(300.0, r.legTimes[0].trafficSeconds!!, 0.0)
        assertEquals(400.0, r.legTimes[1].typicalSeconds, 0.0)
        assertEquals(1000.0, r.distanceMeters, 0.0)
    }

    @Test fun `a direct trip's one leg, and legs that do not tile the trip, are dropped`() {
        val s = summary(trafficAt10 = true, trafficAt11 = false)
        assertTrue(DirectionsParser.parse(rootWithLegs(s, listOf(leg(1000.0, 600.0, 720.0)))).single().legTimes.isEmpty())
        assertTrue(DirectionsParser.parse(rootWithLegs(s, listOf(leg(400.0, 200.0, 300.0), leg(900.0, 400.0, 420.0)))).single().legTimes.isEmpty())
        assertTrue(DirectionsParser.parse(root(s)).single().legTimes.isEmpty())
    }

    @Test fun `compiled paths read the in-traffic time at its known index`() {
        val r = DirectionsParser.parse(root(summary(trafficAt10 = true, trafficAt11 = false))).single()
        assertEquals(1000.0, r.distanceMeters, 0.0)
        assertEquals(600.0, r.durationSeconds, 0.0)
        assertEquals(720.0, r.durationInTrafficSeconds!!, 0.0)
        assertEquals("via I-80", r.summary)
    }

    @Test fun `a remote path override follows a moved field`() {
        val moved = root(summary(trafficAt10 = false, trafficAt11 = true))
        assertNull(DirectionsParser.parse(moved).single().durationInTrafficSeconds)
        val paths = Calibration.DEFAULT_DIRECTIONS_PATHS + ("traffic" to listOf(11, 0))
        assertEquals(720.0, DirectionsParser.parse(moved, paths).single().durationInTrafficSeconds!!, 0.0)
    }
}
