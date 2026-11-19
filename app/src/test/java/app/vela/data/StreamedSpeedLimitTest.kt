package app.vela.data

import app.vela.core.model.LatLng
import app.vela.core.util.MvtLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.math.cos

class StreamedSpeedLimitTest {
    private val noon = LocalDateTime.of(2026, 10, 5, 12, 0)
    private val originLat = 38.55
    private val originLng = -121.74

    private fun pt(eastM: Double, northM: Double): LatLng {
        val k = 111_320.0 * cos(Math.toRadians(originLat))
        return LatLng(originLat + northM / 111_320.0, originLng + eastM / k)
    }

    private fun line(limit: String, vararg pts: LatLng): MvtLines.Line =
        MvtLines.Line(mapOf("maxspeed" to limit), pts.toList())

    @Test
    fun bridgeCrossingPrefersHeadingAlignedRoadOverCloserLowerRoad() {
        // Motorway underneath runs east-west at y = 0 (100 km/h).
        // Overpass road runs north-south at x = 0 (50 km/h).
        val motorwayBelow = line("100", pt(-100.0, 0.0), pt(100.0, 0.0))
        val bridgeAbove = line("50", pt(0.0, -100.0), pt(0.0, 100.0))
        // Fix sits 4 m north of the motorway centerline and 7 m east of the bridge centerline.
        val fix = pt(7.0, 4.0)
        val snap = StreamedSpeedLimit.snapLine(
            listOf(motorwayBelow, bridgeAbove),
            fix.lat, fix.lng,
            headingDeg = 0.0,
            prevPts = null,
            now = noon,
        )
        assertEquals(50.0, snap?.kmh ?: -1.0, 1e-6)
    }

    @Test
    fun shallowSkewedOverpassKeepsConnectedBridgeRoad() {
        // Skewed overpass crosses a 100 km/h highway at ~20 degrees.
        // Without continuity, at x=0,y=0 both ways are equidistant; slightly shifted, the wrong one is closer.
        val bridge = line("30", pt(-35.0, -100.0), pt(35.0, 100.0)) // ~19.3 deg
        val motorwayBelow = line("100", pt(0.0, -100.0), pt(0.0, 100.0)) // 0 deg
        // Fix is 1.5 m closer to the motorway below, but we were just on the bridge.
        val fix = pt(0.0, 0.0)
        val snap = StreamedSpeedLimit.snapLine(
            listOf(motorwayBelow, bridge),
            fix.lat, fix.lng,
            headingDeg = 15.0,
            prevPts = bridge.points,
            now = noon,
        )
        assertEquals(30.0, snap?.kmh ?: -1.0, 1e-6)
    }

    @Test
    fun freeDriveDoesNotPickUpParallelMotorway() {
        // Driving north on a 30 km/h service road at x = 0, parallel to a 100 km/h motorway 25 m east.
        val road30 = line("30", pt(0.0, -50.0), pt(0.0, 450.0))
        val motorway100 = line("100", pt(25.0, -50.0), pt(25.0, 450.0))
        val fix = pt(0.0, 0.0)
        // Slight 4 deg east drift in GPS heading would send a 300 m straight ray 21 m east, near the motorway.
        val upcoming = StreamedSpeedLimit.upcomingInLines(
            fix.lat, fix.lng,
            headingDeg = 4.0,
            lookAheadMeters = 300.0,
            currentLimitKmh = 30.0,
            prevPts = road30.points,
            now = noon,
        ) { _, _ -> listOf(road30, motorway100) }
        assertNull(upcoming)
    }

    @Test
    fun freeDriveStopsBeforeSidewaysSBendInsteadOfShootingAcrossIt() {
        // 30 km/h road heads north (0..60 m), then swings ~100 m west in a sideways S-bend,
        // before joining a 50 km/h road heading north again.
        // Another unrelated 50 km/h road sits straight north beyond the bend at x = 0.
        val approach = line(
            "30",
            pt(0.0, -40.0),
            pt(0.0, 60.0),
            pt(-100.0, 110.0), // sharp >60 deg turn west
            pt(-100.0, 170.0),
        )
        val afterBend = line("50", pt(-100.0, 170.0), pt(-100.0, 350.0))
        val unrelatedStraight = line("50", pt(0.0, 150.0), pt(0.0, 350.0))
        val lines = listOf(approach, afterBend, unrelatedStraight)

        val beforeBend = pt(0.0, 0.0)
        val beforeRes = StreamedSpeedLimit.upcomingInLines(
            beforeBend.lat, beforeBend.lng,
            headingDeg = 0.0,
            lookAheadMeters = 300.0,
            currentLimitKmh = 30.0,
            prevPts = approach.points,
            now = noon,
        ) { _, _ -> lines }
        assertNull(beforeRes)

        // Once on the straight section after the S-bend (at x = -100, y = 120), the 50 km/h change 50 m ahead is shown.
        val afterExit = pt(-100.0, 120.0)
        val afterRes = StreamedSpeedLimit.upcomingInLines(
            afterExit.lat, afterExit.lng,
            headingDeg = 0.0,
            lookAheadMeters = 300.0,
            currentLimitKmh = 30.0,
            prevPts = approach.points,
            now = noon,
        ) { _, _ -> lines }
        assertEquals(50.0, afterRes?.first ?: -1.0, 1e-6)
        assertTrue((afterRes?.second ?: 0.0) in 40.0..60.0)
    }

    @Test
    fun freeDriveFollowsConnectedRoadPastSideStreetToLimitChange() {
        val seg1 = line("30", pt(0.0, -40.0), pt(0.0, 120.0))
        val sideStreet = line("50", pt(0.0, 70.0), pt(80.0, 70.0))
        val seg2 = line("50", pt(0.0, 120.0), pt(0.0, 350.0))
        val fix = pt(0.0, 0.0)
        val res = StreamedSpeedLimit.upcomingInLines(
            fix.lat, fix.lng,
            headingDeg = 0.0,
            lookAheadMeters = 300.0,
            currentLimitKmh = 30.0,
            prevPts = seg1.points,
            now = noon,
        ) { _, _ -> listOf(seg1, sideStreet, seg2) }
        assertEquals(50.0, res?.first ?: -1.0, 1e-6)
        assertTrue((res?.second ?: 0.0) in 110.0..130.0)
    }
}
