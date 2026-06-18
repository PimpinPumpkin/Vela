package app.vela.core

import app.vela.core.model.LatLng
import app.vela.core.nav.Heading
import org.junit.Assert.assertEquals
import org.junit.Test

class HeadingTest {
    private fun east() = listOf(LatLng(38.5, -121.7), LatLng(38.5, -121.69), LatLng(38.5, -121.68))
    private fun north() = listOf(LatLng(38.5, -121.7), LatLng(38.51, -121.7), LatLng(38.52, -121.7))

    @Test fun cardinalEast() = assertEquals("east", Heading.initialCardinal(east()))
    @Test fun cardinalNorth() = assertEquals("north", Heading.initialCardinal(north()))

    @Test fun injectsCardinalIntoHeadToward() {
        assertEquals("Head east on F St", Heading.withCardinal("Head toward F St", east()))
    }

    @Test fun injectsCardinalIntoBareHead() {
        assertEquals("Head north on F St", Heading.withCardinal("Head F St", north()))
    }

    @Test fun leavesTurnsAlone() {
        assertEquals("Turn left onto Main St", Heading.withCardinal("Turn left onto Main St", east()))
    }

    @Test fun leavesExistingCardinalAlone() {
        assertEquals("Head south on F St", Heading.withCardinal("Head south on F St", east()))
    }
}
