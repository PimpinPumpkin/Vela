package app.vela.core.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    // --- Attempts start lean and ESCALATE (issue #258, second cause) -------------------------
    // A mid-drive reroute is single-shot on purpose. On a genuinely flaky link that can fail over
    // and over while ending nav and starting again works first time - because a fresh plan is not
    // urgent and gets the full retry ladder. That was the reported workaround, so the retry ladder
    // has to become reachable without restarting.

    @Test fun `the first attempts are lean and fast`() {
        val a = NavSession.rerouteAttempt(0)
        assertTrue(a.urgent)
        assertEquals(NavSession.REROUTE_FETCH_TIMEOUT_MS, a.timeoutMs)
        assertTrue(NavSession.rerouteAttempt(1).urgent)
    }

    @Test fun `after repeated failures it uses the full ladder and allows it longer`() {
        val a = NavSession.rerouteAttempt(NavSession.REROUTE_ESCALATE_AFTER)
        assertFalse("the ladder is the whole point of escalating", a.urgent)
        assertTrue("the ladder needs longer than a single shot", a.timeoutMs > NavSession.REROUTE_FETCH_TIMEOUT_MS)
        assertFalse(NavSession.rerouteAttempt(9).urgent)
    }

    // The stuck-job rule must judge an attempt by ITS OWN deadline: an escalated attempt is allowed
    // longer, and measuring it against the lean deadline would declare a healthy fetch wedged and
    // kill it right before it succeeded - reintroducing the bug in a new form.
    @Test fun `an escalated attempt is not declared wedged at the lean deadline`() {
        val started = 1_000L
        val justPastLean = started + NavSession.REROUTE_FETCH_TIMEOUT_MS + NavSession.REROUTE_STUCK_GRACE_MS + 1
        assertEquals(
            RerouteGate.SKIP_IN_FLIGHT,
            NavSession.rerouteGate(true, started, 0L, justPastLean, NavSession.REROUTE_LADDER_TIMEOUT_MS),
        )
        val pastLadder = started + NavSession.REROUTE_LADDER_TIMEOUT_MS + NavSession.REROUTE_STUCK_GRACE_MS + 1
        assertEquals(
            RerouteGate.ABANDON_STUCK_AND_START,
            NavSession.rerouteGate(true, started, 0L, pastLadder, NavSession.REROUTE_LADDER_TIMEOUT_MS),
        )
    }
}
