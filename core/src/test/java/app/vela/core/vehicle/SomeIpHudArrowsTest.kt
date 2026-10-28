package app.vela.core.vehicle

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.RoundaboutGeometry
import app.vela.core.vehicle.SomeIpHudArrows.Table
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SomeIpHudArrowsTest {
    private fun m(type: ManeuverType, clockwise: Boolean? = null) = Maneuver(
        type, "", LatLng(38.5449, -121.7405), 0.0, 0.0,
        roundabout = clockwise?.let { RoundaboutGeometry(90.0, it) },
    )

    private val launchermap = mapOf(
        ManeuverType.TURN_LEFT to 1, ManeuverType.TURN_RIGHT to 2,
        ManeuverType.SLIGHT_LEFT to 3, ManeuverType.SLIGHT_RIGHT to 5,
        ManeuverType.KEEP_LEFT to 3, ManeuverType.KEEP_RIGHT to 5,
        ManeuverType.FORK_LEFT to 3, ManeuverType.FORK_RIGHT to 5,
        ManeuverType.RAMP_LEFT to 3, ManeuverType.RAMP_RIGHT to 5,
        ManeuverType.SHARP_LEFT to 7, ManeuverType.SHARP_RIGHT to 8,
        ManeuverType.UTURN to 9,
        ManeuverType.DEPART to 11, ManeuverType.CONTINUE to 11, ManeuverType.STRAIGHT to 11, ManeuverType.MERGE to 11,
        ManeuverType.ROUNDABOUT to 13, ManeuverType.EXIT_ROUNDABOUT to 24,
        ManeuverType.ARRIVE to 48, ManeuverType.UNKNOWN to 0,
    )

    private val native = launchermap + mapOf(
        ManeuverType.SHARP_LEFT to 1, ManeuverType.SHARP_RIGHT to 2, ManeuverType.UTURN to 7,
        ManeuverType.ROUNDABOUT to 99, ManeuverType.EXIT_ROUNDABOUT to 99,
        ManeuverType.ARRIVE to 99, ManeuverType.UNKNOWN to 99,
    )

    @Test fun `the stock sender's ids for every maneuver type`() {
        assertEquals(ManeuverType.entries.toSet(), launchermap.keys)
        for ((type, id) in launchermap) assertEquals(type.name, id, SomeIpHudArrows.of(m(type), Table.LAUNCHERMAP))
    }

    @Test fun `the native-arrow ids for every maneuver type`() {
        for ((type, id) in native) assertEquals(type.name, id, SomeIpHudArrows.of(m(type), Table.NATIVE_ARROW))
    }

    @Test fun `clockwise roundabouts have their own stock ids`() {
        assertEquals(14, SomeIpHudArrows.of(m(ManeuverType.ROUNDABOUT, clockwise = true), Table.LAUNCHERMAP))
        assertEquals(23, SomeIpHudArrows.of(m(ManeuverType.EXIT_ROUNDABOUT, clockwise = true), Table.LAUNCHERMAP))
        assertEquals(99, SomeIpHudArrows.of(m(ManeuverType.ROUNDABOUT, clockwise = true), Table.NATIVE_ARROW))
    }

    @Test fun `no maneuver is none in both`() {
        assertEquals(0, SomeIpHudArrows.of(null, Table.LAUNCHERMAP))
        assertEquals(0, SomeIpHudArrows.of(null, Table.NATIVE_ARROW))
    }

    @Test fun `the HUD's ids are not the FCE cluster's`() {
        // The two vocabularies share no code for a turn: mixing them up draws a wrong arrow.
        for (type in listOf(ManeuverType.TURN_LEFT, ManeuverType.TURN_RIGHT, ManeuverType.STRAIGHT, ManeuverType.ARRIVE)) {
            assertNotEquals(type.name, FceIcons.of(m(type)), SomeIpHudArrows.of(m(type), Table.LAUNCHERMAP))
        }
    }
}
