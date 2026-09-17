package app.vela.core.nav

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which plate cameras count as "on the route" ([CameraFacing]): a camera beside the road only
 * reads the cars on it when it looks along the road, not across it.
 */
class CameraFacingTest {

    // A straight road heading due north through Davis, about 1.1 km long.
    private val north = listOf(LatLng(38.540, -121.740), LatLng(38.550, -121.740))

    // A camera 20 m east of that road, halfway along.
    private val beside = LatLng(38.545, -121.740 + 20.0 / (111_320.0 * Math.cos(Math.toRadians(38.545))))

    @Test fun `facing along the road counts`() {
        assertTrue(CameraFacing.onRoute(north, beside, 0.0, 45.0))
        assertTrue("a little off-axis is fine", CameraFacing.onRoute(north, beside, 40.0, 45.0))
        assertTrue("right at the limit", CameraFacing.onRoute(north, beside, 310.0, 45.0))
    }

    @Test fun `facing against traffic counts too`() {
        assertTrue(CameraFacing.onRoute(north, beside, 180.0, 45.0))
        assertTrue(CameraFacing.onRoute(north, beside, 200.0, 45.0))
    }

    @Test fun `facing across the road is excluded`() {
        assertFalse(CameraFacing.onRoute(north, beside, 90.0, 45.0))
        assertFalse(CameraFacing.onRoute(north, beside, 270.0, 45.0))
        assertFalse("just past the limit", CameraFacing.onRoute(north, beside, 55.0, 45.0))
    }

    @Test fun `a camera with no facing counts`() {
        assertTrue(CameraFacing.onRoute(north, beside, null, 45.0))
        assertTrue(CameraFacing.onRoute(north, beside, CameraFacing.parse(""), 45.0))
    }

    @Test fun `a camera too far from the road never counts`() {
        val far = LatLng(38.545, -121.739) // about 87 m east
        assertFalse(CameraFacing.onRoute(north, far, 0.0, 45.0))
        assertFalse(CameraFacing.onRoute(north, far, null, 45.0))
    }

    // North for about 550 m, then east. Cameras sit near the corner; whichever leg is nearer
    // decides which axis the camera must line up with.
    @Test fun `beside a corner the nearest segment decides`() {
        val corner = LatLng(38.545, -121.740)
        val route = listOf(LatLng(38.540, -121.740), corner, LatLng(38.545, -121.734))
        // 30 m south of the corner, 10 m west of the north leg: the north leg is nearest.
        val onNorthLeg = LatLng(38.545 - 30.0 / 111_320.0, -121.740 - 10.0 / (111_320.0 * Math.cos(Math.toRadians(38.545))))
        assertTrue(CameraFacing.onRoute(route, onNorthLeg, 0.0, 45.0))
        assertFalse(CameraFacing.onRoute(route, onNorthLeg, 90.0, 45.0))
        // 30 m east of the corner, 10 m north of the east leg: the east leg is nearest.
        val onEastLeg = LatLng(38.545 + 10.0 / 111_320.0, -121.740 + 30.0 / (111_320.0 * Math.cos(Math.toRadians(38.545))))
        assertTrue(CameraFacing.onRoute(route, onEastLeg, 90.0, 45.0))
        assertTrue(CameraFacing.onRoute(route, onEastLeg, 270.0, 45.0))
        assertFalse(CameraFacing.onRoute(route, onEastLeg, 0.0, 45.0))
    }

    @Test fun `nearest segment reports distance and bearing`() {
        val n = CameraFacing.nearestSegment(north, beside)!!
        assertEquals(20.0, n.distanceM, 0.5)
        assertEquals(0.0, n.bearingDeg, 0.01)
        val east = listOf(LatLng(38.540, -121.740), LatLng(38.540, -121.730))
        assertEquals(90.0, CameraFacing.nearestSegment(east, LatLng(38.5401, -121.735))!!.bearingDeg, 0.01)
    }

    @Test fun `degenerate routes give nothing`() {
        assertNull(CameraFacing.nearestSegment(listOf(north[0]), beside))
        assertNull(CameraFacing.nearestSegment(listOf(north[0], north[0]), beside))
    }

    @Test fun `parse and axis math`() {
        assertEquals(165.0, CameraFacing.parse("165")!!, 0.0)
        assertEquals(22.5, CameraFacing.parse(" 22.5 ")!!, 0.0)
        assertEquals(350.0, CameraFacing.parse("-10")!!, 0.0)
        assertNull(CameraFacing.parse(""))
        assertNull(CameraFacing.parse(null))
        assertNull(CameraFacing.parse("NE-SW"))
        assertEquals(20.0, CameraFacing.axisDiff(350.0, 10.0), 1e-9)
        assertEquals(0.0, CameraFacing.axisDiff(0.0, 180.0), 1e-9)
        assertEquals(90.0, CameraFacing.axisDiff(0.0, 270.0), 1e-9)
        assertEquals(10.0, CameraFacing.axisDiff(10.0, 180.0), 1e-9)
    }
}
