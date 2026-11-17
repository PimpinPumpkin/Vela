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

    // A corner with several plate readers is one announcement, not three back to back.
    @Test fun `cameras close together are grouped`() {
        val groups = CameraAlerts.group(listOf(1_000.0, 1_020.0, 1_055.0, 2_000.0, 2_041.0))
        assertEquals(
            listOf(CameraAlerts.Group(1_000.0, 3), CameraAlerts.Group(2_000.0, 1), CameraAlerts.Group(2_041.0, 1)),
            groups,
        )
        assertTrue(CameraAlerts.group(emptyList()).isEmpty())
        assertEquals(listOf(CameraAlerts.Group(500.0, 1)), CameraAlerts.group(listOf(500.0)))
    }

    @Test fun `a keyed list rebuilt each scan announces a camera once`() {
        // At 20 m/s the lead is 240 m: the first is inside it, the second not yet.
        val scan1 = listOf("a" to 200.0, "b" to 700.0)
        assertEquals("a", CameraAlerts.dueKeyed(scan1, 0.0, 20.0, emptySet()))
        // The next scan sees the same camera nearer under the same key: already announced.
        val scan2 = listOf("a" to 50.0, "b" to 550.0)
        assertEquals(null, CameraAlerts.dueKeyed(scan2, 0.0, 20.0, setOf("a")))
        // Driven on since the scan, the second comes into the lead window.
        assertEquals("b", CameraAlerts.dueKeyed(scan2, 350.0, 20.0, setOf("a")))
        // A camera behind, or a crawl, says nothing.
        assertEquals(null, CameraAlerts.dueKeyed(listOf("c" to -20.0), 0.0, 20.0, emptySet()))
        assertEquals(null, CameraAlerts.dueKeyed(scan1, 0.0, 1.0, emptySet()))
        assertEquals(null, CameraAlerts.dueKeyed(emptyList(), 0.0, 20.0, emptySet()))
    }

    @Test fun `custom lead distance overrides dynamic calculation`() {
        // At 25 m/s, dynamic lead is 300 m.
        // At traveledM = 550 m (450 m ahead of cam at 1000 m):
        assertNull(CameraAlerts.due(cams, traveledM = 550.0, speedMps = 25.0, spoken = emptySet()))
        // Custom 500 m lead triggers:
        assertEquals(0, CameraAlerts.due(cams, traveledM = 550.0, speedMps = 25.0, spoken = emptySet(), customLeadM = 500.0))
        // Custom 200 m lead suppresses when dynamic would trigger at 750 m (250 m ahead):
        assertNull(CameraAlerts.due(cams, traveledM = 750.0, speedMps = 25.0, spoken = emptySet(), customLeadM = 200.0))

        // leadMAt per-index resolution:
        val customResolv: (Int) -> Double? = { idx -> if (idx == 0) 500.0 else 200.0 }
        assertEquals(0, CameraAlerts.due(cams, traveledM = 550.0, speedMps = 25.0, spoken = emptySet(), leadMAt = customResolv))
    }
}
