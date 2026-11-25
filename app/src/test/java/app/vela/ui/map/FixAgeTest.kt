package app.vela.ui.map

import app.vela.core.model.LatLng
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class FixAgeTest {
    @After fun reset() = FixAge.clear()

    @Test fun `the age of the fix last set, from its own clock`() {
        val fix = LatLng(38.5449, -121.7405)
        FixAge.set(fix, 10_000_000_000L)
        assertEquals(150L, FixAge.ageMs(fix, 10_150_000_000L))
    }

    @Test fun `another fix, an equal copy, or no clock reads as no age`() {
        val fix = LatLng(38.5449, -121.7405)
        FixAge.set(fix, 10_000_000_000L)
        assertEquals(0L, FixAge.ageMs(LatLng(38.5449, -121.7405), 10_150_000_000L))
        assertEquals(0L, FixAge.ageMs(null, 10_150_000_000L))
        FixAge.set(fix, 0L)
        assertEquals(0L, FixAge.ageMs(fix, 10_150_000_000L))
    }

    @Test fun `a stale fix is not carried forward`() {
        val fix = LatLng(38.5449, -121.7405)
        FixAge.set(fix, 10_000_000_000L)
        assertEquals(FixAge.MAX_MS, FixAge.ageMs(fix, 10_000_000_000L + FixAge.MAX_MS * 1_000_000L))
        assertEquals(0L, FixAge.ageMs(fix, 10_000_000_000L + (FixAge.MAX_MS + 1) * 1_000_000L))
        assertEquals(0L, FixAge.ageMs(fix, 9_000_000_000L))
    }
}
