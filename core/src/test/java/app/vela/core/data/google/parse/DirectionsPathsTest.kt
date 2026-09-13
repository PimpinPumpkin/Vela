package app.vela.core.data.google.parse

import app.vela.core.config.Calibration
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
