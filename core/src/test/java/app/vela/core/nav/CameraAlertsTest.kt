package app.vela.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a speed-camera warning fires ([CameraAlerts]).
 *
 * The cases worth pinning are the ones that make a warning system annoying rather than useful:
 * firing twice for one camera, firing about a camera already behind you, or talking at you while
 * you sit stationary next to one.
 */
class CameraAlertsTest {

    private val cams = listOf(1_000.0, 5_000.0, 5_400.0)

    // 25 m/s (~56 mph) -> 300 m of lead, so the warning lands about 12 s out.
    @Test fun `warns once inside the lead window`() {
        assertNull("too far out to warn yet", CameraAlerts.due(cams, traveledM = 600.0, speedMps = 25.0, spoken = emptySet()))
        assertEquals(0, CameraAlerts.due(cams, traveledM = 750.0, speedMps = 25.0, spoken = emptySet()))
    }

    @Test fun `a camera already warned about is not repeated`() {
        assertNull(CameraAlerts.due(cams, traveledM = 900.0, speedMps = 25.0, spoken = setOf(0)))
    }

    // Creeping toward a camera in traffic must not re-arm it once spoken.
    @Test fun `crawling toward an already-warned camera stays silent`() {
        for (t in 800..990 step 10) {
            assertNull(CameraAlerts.due(cams, traveledM = t.toDouble(), speedMps = 3.0, spoken = setOf(0)))
        }
    }

    @Test fun `a camera behind you is never announced`() {
        assertNull("no warning about something passed", CameraAlerts.due(cams, traveledM = 1_200.0, speedMps = 25.0, spoken = emptySet()))
    }

    @Test fun `stationary means silence`() {
        assertNull(CameraAlerts.due(cams, traveledM = 900.0, speedMps = 0.5, spoken = emptySet()))
    }

    @Test fun `the nearest unannounced camera wins when two are close together`() {
        val i = CameraAlerts.due(cams, traveledM = 4_900.0, speedMps = 50.0, spoken = emptySet())
        assertEquals("the closer one is the one you need to hear about", 1, i)
        // Having announced it, the next one becomes due.
        assertEquals(2, CameraAlerts.due(cams, traveledM = 4_900.0, speedMps = 50.0, spoken = setOf(1)))
    }

    // Lead scales with speed, which is the whole reason it is not a fixed distance.
    @Test fun `lead distance scales with speed but stays bounded`() {
        assertEquals(CameraAlerts.MIN_LEAD_M, CameraAlerts.leadDistanceM(5.0), 0.01)  // town: floored
        assertEquals(300.0, CameraAlerts.leadDistanceM(25.0), 0.01)                   // 12 s at 25 m/s
        assertEquals(CameraAlerts.MAX_LEAD_M, CameraAlerts.leadDistanceM(80.0), 0.01) // capped
        assertTrue(CameraAlerts.leadDistanceM(40.0) > CameraAlerts.leadDistanceM(20.0))
    }

    @Test fun `no cameras means nothing to say`() {
        assertNull(CameraAlerts.due(emptyList(), traveledM = 100.0, speedMps = 25.0, spoken = emptySet()))
    }
}
