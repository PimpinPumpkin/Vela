package app.vela.core.nav

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCornersTest {
    private val lat = 38.5449
    private val lng0 = -121.7405 // Davis fixture
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun at(eastM: Double, northM: Double) = LatLng(lat + northM / 111_320.0, lng0 + eastM / mPerDegLng)

    private fun corners(line: List<LatLng>) = RouteCorners.find(line, RouteProjection.cumulative(line))

    @Test fun `a left turn at a street corner is one corner, turning left`() {
        // West 300 m, then south 300 m, a vertex every 10 m.
        val line = (0..30).map { at(-it * 10.0, 0.0) } + (1..30).map { at(-300.0, -it * 10.0) }
        val c = corners(line)
        assertEquals(1, c.size)
        assertEquals(300.0, c[0].atM, 1.0)
        assertEquals(270.0, c[0].inDeg, 1.0)
        assertEquals(180.0, c[0].outDeg, 1.0)
        assertEquals(-90.0, c[0].turnDeg, 1.0)
    }

    @Test fun `a corner the drawn line rounds is one corner, with the roads' own bearings`() {
        val line = RouteSmoothing.roundBends((0..30).map { at(-it * 10.0, 0.0) } + (1..30).map { at(-300.0, -it * 10.0) })
        val c = corners(line)
        assertEquals(1, c.size)
        assertEquals("in the curve's middle", 300.0 - 10.0 + 8.1, c[0].atM, 2.0)
        assertEquals(270.0, c[0].inDeg, 1.0)
        assertEquals(180.0, c[0].outDeg, 1.0)
        assertEquals(-90.0, c[0].turnDeg, 1.0)
    }

    @Test fun `a gentle freeway ramp is no corner`() {
        // A 20 degree bend spread over 100 m.
        val line = ArrayList<LatLng>()
        var x = 0.0; var y = 0.0; var h = 0.0
        for (i in 0..60) {
            line.add(at(x, y))
            if (i in 20..30) h += 2.0
            x += 10.0 * Math.sin(Math.toRadians(h)); y += 10.0 * Math.cos(Math.toRadians(h))
        }
        assertTrue(corners(line).isEmpty())
    }

    @Test fun `a corner drawn with several vertices is still one corner`() {
        // East, then a rounded right turn drawn over 3 m steps, then south.
        val line = (0..20).map { at(it * 10.0, 0.0) } +
            (1..6).map { k -> val a = Math.toRadians(k * 15.0); at(200.0 + 9.0 * Math.sin(a), -9.0 + 9.0 * Math.cos(a)) } +
            (1..20).map { at(209.0, -9.0 - it * 10.0) }
        val c = corners(line)
        assertEquals(1, c.size)
        assertEquals(90.0, c[0].turnDeg, 8.0)
    }

    @Test fun `a car that went straight on is not past the corner, one that turned is`() {
        // East 300 m, then north: a left turn at 300 m.
        val line = (0..30).map { at(it * 10.0, 0.0) } + (1..30).map { at(300.0, it * 10.0) }
        val c = corners(line).single()
        // The engine's position has moved 10 m on, but the car kept going east.
        assertTrue(!RouteCorners.passed(c, 310.0, at(310.0, 0.0)))
        // It turned: the fix is 10 m up the new road.
        assertTrue(RouteCorners.passed(c, 310.0, at(300.0, 10.0)))
        // Far past it, the corner is behind whatever the last fix says.
        assertTrue(RouteCorners.passed(c, 400.0, at(250.0, -40.0)))
        assertEquals(10.0, RouteCorners.outAlong(c, at(300.0, 10.0)), 0.5)
    }
}
