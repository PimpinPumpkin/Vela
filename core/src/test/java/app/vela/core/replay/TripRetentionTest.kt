package app.vela.core.replay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRetentionTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 1_800_000_000_000L

    @Test fun `a drive is kept for 30 days and goes after`() {
        assertFalse(TripRetention.expired(now - 29 * day, now))
        assertFalse(TripRetention.expired(now - 30 * day, now))
        assertTrue(TripRetention.expired(now - 31 * day, now))
    }
}
