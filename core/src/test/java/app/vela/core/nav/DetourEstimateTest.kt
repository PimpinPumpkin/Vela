package app.vela.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetourEstimateTest {
    @Test fun `a real detour rounds to whole minutes`() {
        assertEquals(4, DetourEstimate.minutesAdded(1_200.0, 1_200.0 + 230.0))
        assertEquals(1, DetourEstimate.minutesAdded(1_200.0, 1_200.0 + 61.0))
    }

    @Test fun `a detour under a minute still reads as one, never zero`() {
        assertEquals(1, DetourEstimate.minutesAdded(1_200.0, 1_200.0 + 25.0))
    }

    @Test fun `too small to mean anything shows nothing`() {
        assertNull(DetourEstimate.minutesAdded(1_200.0, 1_200.0 + 5.0))
        assertNull(DetourEstimate.minutesAdded(1_200.0, 1_200.0))
    }

    @Test fun `a place already on the way never reads as a saving`() {
        assertNull(DetourEstimate.minutesAdded(1_200.0, 900.0))
    }

    @Test fun `an implausible figure is a bad fetch, not a detour`() {
        assertNull(DetourEstimate.minutesAdded(1_200.0, 1_200.0 + 4 * 60 * 60.0))
    }

    @Test fun `a missing figure on either side shows nothing`() {
        assertNull(DetourEstimate.minutesAdded(0.0, 900.0))
        assertNull(DetourEstimate.minutesAdded(1_200.0, 0.0))
    }
}
