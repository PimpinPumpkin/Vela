package app.vela.core.nav

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Rerouting must be single-flight but never permanently blocked (issue #258: "Re-routing" shown
 * forever, only a nav restart cleared it, reported on a real drive where touching the phone is
 * itself the hazard).
 *
 * The failing shape: a fetch wedged in non-cancellable I/O outlives its own deadline, so the job
 * stays active; the old guard read that as "a reroute is in flight" and rejected every later
 * request for the rest of the drive. The deadline on the fetch cannot be the only protection,
 * because the thing being bounded is exactly what ignores cancellation.
 */
class RerouteGateTest {
    private val deadline = NavSession.REROUTE_FETCH_TIMEOUT_MS
    private val grace = NavSession.REROUTE_STUCK_GRACE_MS
    private val cooldown = NavSession.REROUTE_COOLDOWN_MS

    @Test fun `idle session starts a reroute`() {
        assertEquals(RerouteGate.START, NavSession.rerouteGate(false, 0L, 0L, 1_000_000L))
    }

    @Test fun `a genuinely in-flight fetch is not duplicated`() {
        val now = 1_000_000L
        assertEquals(
            RerouteGate.SKIP_IN_FLIGHT,
            NavSession.rerouteGate(true, now - deadline / 2, 0L, now),
        )
    }

    @Test fun `a job wedged past the deadline is abandoned, not obeyed forever`() {
        // THE REGRESSION: before the fix this returned "in flight" for the rest of the drive.
        val now = 1_000_000L
        assertEquals(
            RerouteGate.ABANDON_STUCK_AND_START,
            NavSession.rerouteGate(true, now - (deadline + grace), 0L, now),
        )
    }

    @Test fun `a wedged job stays abandonable however long it hangs`() {
        val now = 9_000_000L
        assertEquals(
            RerouteGate.ABANDON_STUCK_AND_START,
            NavSession.rerouteGate(true, now - 60 * 60_000L, 0L, now),
        )
    }

    @Test fun `a just-adopted reroute is not immediately re-run`() {
        val now = 1_000_000L
        assertEquals(
            RerouteGate.SKIP_COOLDOWN,
            NavSession.rerouteGate(false, 0L, now - cooldown / 2, now),
        )
    }

    @Test fun `the cooldown expires`() {
        val now = 1_000_000L
        assertEquals(
            RerouteGate.START,
            NavSession.rerouteGate(false, 0L, now - cooldown - 1, now),
        )
    }

    @Test fun `a wedged job outranks the cooldown so a stuck drive always recovers`() {
        val now = 1_000_000L
        assertEquals(
            RerouteGate.ABANDON_STUCK_AND_START,
            NavSession.rerouteGate(true, now - (deadline + grace), now - 1, now),
        )
    }
}
