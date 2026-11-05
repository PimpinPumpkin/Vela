package app.vela.core.vehicle

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.RoundaboutGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FceClusterTest {
    private fun m(type: ManeuverType, exit: Int? = null, clockwise: Boolean? = null) = Maneuver(
        type, "", LatLng(38.5449, -121.7405), 0.0, 0.0,
        roundabout = clockwise?.let { RoundaboutGeometry(90.0, it) }, roundaboutExit = exit,
    )

    @Test fun `property ids are the HAL's`() {
        assertEquals(0x214000a5, FceProperties.NAVI_STATUS)
        assertEquals(0x21400099, FceProperties.ICON)
        assertEquals(0x2140009a, FceProperties.ICON_SET_2)
        assertEquals(0x21400098, FceProperties.DISTANCE)
    }

    @Test fun `every maneuver type has a glyph or none`() {
        val expected = mapOf(
            ManeuverType.TURN_LEFT to 19, ManeuverType.TURN_RIGHT to 20,
            ManeuverType.SLIGHT_LEFT to 18, ManeuverType.SLIGHT_RIGHT to 17,
            ManeuverType.KEEP_LEFT to 18, ManeuverType.KEEP_RIGHT to 17,
            ManeuverType.FORK_LEFT to 18, ManeuverType.FORK_RIGHT to 17,
            ManeuverType.RAMP_LEFT to 18, ManeuverType.RAMP_RIGHT to 17,
            ManeuverType.SHARP_LEFT to 21, ManeuverType.SHARP_RIGHT to 22,
            ManeuverType.UTURN to 23,
            ManeuverType.DEPART to 16, ManeuverType.CONTINUE to 16, ManeuverType.STRAIGHT to 16, ManeuverType.MERGE to 16,
            ManeuverType.ARRIVE to 3, ManeuverType.EXIT_ROUNDABOUT to 52, ManeuverType.ROUNDABOUT to 53,
            ManeuverType.UNKNOWN to 0,
        )
        assertEquals(ManeuverType.entries.toSet(), expected.keys)
        for ((type, icon) in expected) assertEquals(type.name, icon, FceIcons.of(m(type)))
        assertEquals(FceIcons.NONE, FceIcons.of(null))
    }

    @Test fun `roundabout exits one to ten are consecutive, past ten the plain roundabout`() {
        assertEquals(42, FceIcons.of(m(ManeuverType.ROUNDABOUT, exit = 1)))
        assertEquals(44, FceIcons.of(m(ManeuverType.ROUNDABOUT, exit = 3)))
        assertEquals(51, FceIcons.of(m(ManeuverType.ROUNDABOUT, exit = 10)))
        assertEquals(53, FceIcons.of(m(ManeuverType.ROUNDABOUT, exit = 11)))
        assertEquals(53, FceIcons.of(m(ManeuverType.ROUNDABOUT, exit = 0)))
        // No clockwise glyph is known: a clockwise roundabout keeps its exit number.
        assertEquals(43, FceIcons.of(m(ManeuverType.ROUNDABOUT, exit = 2, clockwise = true)))
    }

    @Test fun `distance rounds the way the driver reads it and fits 17 bits`() {
        assertEquals(0, FceIcons.distance(0.0))
        assertEquals(0, FceIcons.distance(-5.0))
        assertEquals(0, FceIcons.distance(Double.NaN))
        assertEquals(10, FceIcons.distance(14.9))
        assertEquals(990, FceIcons.distance(994.0))
        assertEquals(1000, FceIcons.distance(996.0))
        assertEquals(1500, FceIcons.distance(1530.0))
        assertEquals(131_000, FceIcons.distance(131_000.0))
        assertEquals(131_071, FceIcons.distance(5_000_000.0))
    }

    private class Recorder(var failing: Set<Int> = emptySet()) : FcePropertyWriter {
        val writes = mutableListOf<Pair<Int, Int>>()
        override fun setInt(propertyId: Int, value: Int): Boolean {
            writes += propertyId to value
            return propertyId !in failing
        }
    }

    @Test fun `status opens the view before the glyph and the distance`() {
        val r = Recorder()
        val out = FceClusterOutput(r)
        out.update(FceIcons.LEFT, 300)
        assertEquals(
            listOf(
                FceProperties.NAVI_STATUS to 2,
                FceProperties.ICON_SET_2 to 0,
                FceProperties.ICON to FceIcons.LEFT,
                FceProperties.DISTANCE to 300,
            ),
            r.writes,
        )
        assertTrue(out.active)
    }

    @Test fun `only what changed is written again`() {
        val r = Recorder()
        val out = FceClusterOutput(r)
        out.update(FceIcons.LEFT, 300)
        r.writes.clear()
        out.update(FceIcons.LEFT, 300)
        assertEquals(emptyList<Pair<Int, Int>>(), r.writes)
        out.update(FceIcons.LEFT, 290)
        assertEquals(listOf(FceProperties.DISTANCE to 290), r.writes)
        r.writes.clear()
        out.update(FceIcons.RIGHT, 290)
        assertEquals(listOf(FceProperties.ICON_SET_2 to 0, FceProperties.ICON to FceIcons.RIGHT), r.writes)
    }

    @Test fun `nothing else goes out until the view opened`() {
        val r = Recorder(failing = setOf(FceProperties.NAVI_STATUS))
        val out = FceClusterOutput(r)
        out.update(FceIcons.LEFT, 300)
        assertEquals(listOf(FceProperties.NAVI_STATUS to 2), r.writes)
        assertFalse(out.active)
        r.failing = emptySet()
        out.update(FceIcons.LEFT, 300)
        assertTrue(out.active)
        assertEquals(FceProperties.ICON to FceIcons.LEFT, r.writes[3])
    }

    @Test fun `a failed write is retried on the next update`() {
        val r = Recorder(failing = setOf(FceProperties.ICON))
        val out = FceClusterOutput(r)
        out.update(FceIcons.LEFT, 300)
        r.failing = emptySet()
        r.writes.clear()
        out.update(FceIcons.LEFT, 300)
        assertEquals(listOf(FceProperties.ICON_SET_2 to 0, FceProperties.ICON to FceIcons.LEFT), r.writes)
    }

    @Test fun `standby blanks the glyph and distance and keeps the view open`() {
        val r = Recorder()
        val out = FceClusterOutput(r)
        out.update(FceIcons.LEFT, 300)
        r.writes.clear()
        out.standby()
        assertEquals(listOf(FceProperties.ICON to 0, FceProperties.DISTANCE to 0), r.writes)
        r.writes.clear()
        out.standby()
        assertEquals(emptyList<Pair<Int, Int>>(), r.writes)
        assertTrue(out.active)
    }

    @Test fun `stop is the stock end sequence, once`() {
        val r = Recorder()
        val out = FceClusterOutput(r)
        out.stop()
        assertEquals(emptyList<Pair<Int, Int>>(), r.writes) // never opened, nothing to close
        out.update(FceIcons.LEFT, 300)
        r.writes.clear()
        out.stop()
        assertEquals(
            listOf(
                FceProperties.ICON to 0,
                FceProperties.ICON_SET_2 to 0,
                FceProperties.DISTANCE to 0,
                FceProperties.NAVI_STATUS to 1,
                FceProperties.NAVI_STATUS to 0,
            ),
            r.writes,
        )
        assertFalse(out.active)
        r.writes.clear()
        out.stop()
        assertEquals(emptyList<Pair<Int, Int>>(), r.writes)
    }

    @Test fun `after a reset the next update opens the view again`() {
        val r = Recorder()
        val out = FceClusterOutput(r)
        out.update(FceIcons.LEFT, 300)
        out.reset()
        r.writes.clear()
        out.update(FceIcons.LEFT, 300)
        assertEquals(FceProperties.NAVI_STATUS to 2, r.writes.first())
        assertEquals(4, r.writes.size)
    }
}
