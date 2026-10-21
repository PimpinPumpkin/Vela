package app.vela.core.vehicle

import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Test

class ClusterNavOutputTest {
    private val left = Maneuver(ManeuverType.TURN_LEFT, "", LatLng(38.5, -121.7), 0.0, 0.0)

    private fun out(enabled: Boolean = true, navigating: Boolean = true, paused: Boolean = false,
                    arrived: Boolean = false, maneuver: Maneuver? = left, distance: Double = 300.0) =
        clusterNavOutput(enabled, navigating, paused, arrived, maneuver, distance)

    @Test fun `guiding draws the maneuver and distance`() {
        assertEquals(ClusterNav.Guide(FceIcons.LEFT, 300), out())
    }

    @Test fun `the feature off closes the view even mid-drive`() {
        assertEquals(ClusterNav.Stop, out(enabled = false))
    }

    @Test fun `not navigating and arrival both close the view`() {
        assertEquals(ClusterNav.Stop, out(navigating = false, maneuver = null))
        assertEquals(ClusterNav.Stop, out(arrived = true))
    }

    @Test fun `paused or no maneuver yet keeps the view open and blank`() {
        assertEquals(ClusterNav.Standby, out(paused = true))
        assertEquals(ClusterNav.Standby, out(maneuver = null))
    }

    @Test fun `a route change from arrows to standby to stop is a clean sequence`() {
        assertEquals(ClusterNav.Guide(FceIcons.LEFT, 300), out())
        assertEquals(ClusterNav.Standby, out(maneuver = null)) // rerouting
        assertEquals(ClusterNav.Guide(FceIcons.LEFT, 150), out(distance = 150.0))
        assertEquals(ClusterNav.Stop, out(navigating = false, maneuver = null)) // drive ended
    }
}
