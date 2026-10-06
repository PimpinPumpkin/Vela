package app.vela.core.data

import app.vela.core.model.LatLng
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A reroute must set off the way the car is going (Davis fixture, a line running north). */
class StartsAgainstTest {
    private fun line(vararg dNorthM: Double, eastM: Double = 0.0) = dNorthM.map { LatLng(38.5449 + it / 111_320.0, -121.7405 + eastM / 87_000.0) }

    @Test fun aLineHeadingSouthIsAgainstACarHeadingNorth() {
        assertTrue(RouteGeometry.startsAgainst(line(0.0, -40.0, -120.0), 2.0))
    }

    @Test fun aLineHeadingNorthIsNot() {
        assertFalse(RouteGeometry.startsAgainst(line(0.0, 40.0, 120.0), 358.0))
    }

    @Test fun aTurnOffToTheSideIsNot() {
        // Due east from a car heading north: a turn, not a turn-around.
        val east = listOf(LatLng(38.5449, -121.7405), LatLng(38.5449, -121.7395))
        assertFalse(RouteGeometry.startsAgainst(east, 0.0))
    }

    @Test fun aLineTooShortToJudgeIsNot() {
        assertFalse(RouteGeometry.startsAgainst(line(0.0, -5.0), 0.0))
        assertFalse(RouteGeometry.startsAgainst(line(0.0), 0.0))
    }
}
