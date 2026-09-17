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

    // --- Several reroutes close together (issue #258, 2026-09-17) ---------------------------
    // RerouteNeeded fires on the RISING EDGE of the engine's off-route latch. A request the
    // cooldown turned away used to leave the latch set, so a driver already off a route adopted a
    // few seconds earlier was never rerouted again until they happened back onto the line.

    @Test fun `only the cooldown skip clears the latch for a retry`() {
        assertTrue(NavSession.rerouteSkipRetries(RerouteGate.SKIP_COOLDOWN))
        assertFalse(NavSession.rerouteSkipRetries(RerouteGate.SKIP_IN_FLIGHT))
        assertFalse(NavSession.rerouteSkipRetries(RerouteGate.START))
        assertFalse(NavSession.rerouteSkipRetries(RerouteGate.ABANDON_STUCK_AND_START))
    }

    @Test fun `the fetch keeps room for naming inside every attempt's deadline`() {
        for (streak in 0..4) {
            val a = NavSession.rerouteAttempt(streak)
            assertTrue(a.budgetMs > 0)
            assertTrue(a.budgetMs + NavSession.REROUTE_NAME_SLACK_MS < a.timeoutMs)
        }
    }

    /**
     * A drive, one fix a second, the driver off the line the whole time. Models the session's
     * contract: the engine latch sets after [HITS] deviated fixes and fires once on the edge; a
     * cleared latch starts counting again; a job ends at its outcome time (adopt or fail) or at its
     * deadline; a failure clears the latch. Returns the times at which attempts STARTED.
     */
    private fun simulate(
        adoptedAt: Long?,
        outcome: (startMs: Long) -> Pair<Long, Boolean>, // (duration, adopted)
        endMs: Long,
        retryOnSkip: (RerouteGate) -> Boolean = NavSession::rerouteSkipRetries,
    ): List<Long> {
        val starts = mutableListOf<Long>()
        var hits = 0
        var latched = false
        var clearPending = false
        var jobStart = -1L
        var jobEnd = -1L
        var jobAdopts = false
        var jobDeadline = 0L
        var lastAdopt = adoptedAt ?: 0L
        var streak = 0
        var t = 0L
        while (t <= endMs) {
            // job resolution happens off the location thread, before this fix is handled
            if (jobStart >= 0 && t >= jobEnd) {
                if (jobAdopts) { lastAdopt = jobEnd; latched = false; hits = 0; streak = 0 } else { clearPending = true; streak++ }
                jobStart = -1
            }
            if (clearPending) { latched = false; hits = 0; clearPending = false }
            hits++
            val nowLatched = hits >= HITS
            val edge = nowLatched && !latched
            latched = nowLatched
            if (edge) {
                val gate = NavSession.rerouteGate(jobStart >= 0, jobStart, lastAdopt, t, jobDeadline)
                if (retryOnSkip(gate)) clearPending = true
                if (gate == RerouteGate.START || gate == RerouteGate.ABANDON_STUCK_AND_START) {
                    val attempt = NavSession.rerouteAttempt(streak)
                    val (dur, adopts) = outcome(t)
                    jobStart = t
                    jobDeadline = attempt.timeoutMs
                    jobAdopts = adopts && dur <= attempt.timeoutMs
                    jobEnd = t + minOf(dur, attempt.timeoutMs)
                    starts += t
                }
            }
            t += 1_000L
        }
        return starts
    }

    private val HITS = 3

    @Test fun `off a route adopted a moment ago, the next attempt comes right after the cooldown`() {
        // The export's case: a route adopted at t=0, the driver already off it two fixes later.
        val starts = simulate(adoptedAt = 0L, outcome = { 60_000L to false }, endMs = 30_000L)
        assertTrue("no attempt at all while off-route", starts.isNotEmpty())
        assertTrue(
            "first attempt at ${starts.first()} ms, cooldown is $cooldown ms",
            starts.first() <= cooldown + HITS * 1_000L + 1_000L,
        )
    }

    @Test fun `without the retry the same drive is never rerouted (the bug)`() {
        val starts = simulate(adoptedAt = 0L, outcome = { 60_000L to false }, endMs = 120_000L, retryOnSkip = { false })
        assertTrue(starts.isEmpty())
    }

    @Test fun `a hung router never leaves the driver without an attempt longer than a deadline plus a few seconds`() {
        // Every fetch hangs past its deadline: lean, lean, then the ladder, forever.
        val starts = simulate(adoptedAt = null, outcome = { 10 * 60_000L to false }, endMs = 10 * 60_000L)
        assertTrue(starts.size >= 5)
        val gaps = starts.zipWithNext { a, b -> b - a }
        val maxDeadline = NavSession.REROUTE_LADDER_TIMEOUT_MS
        gaps.forEach { gap -> assertTrue("gap $gap ms", gap <= maxDeadline + (HITS + 1) * 1_000L) }
    }

    @Test fun `a reroute that lands late still leaves the next one reachable`() {
        // Each fetch takes 15 s and adopts a route the driver is already off: adopt, cooldown,
        // retry, adopt, and so on. There is always a next attempt.
        val starts = simulate(adoptedAt = null, outcome = { 15_000L to true }, endMs = 5 * 60_000L)
        val gaps = starts.zipWithNext { a, b -> b - a }
        assertTrue(starts.size >= 5)
        gaps.forEach { gap ->
            assertTrue("gap $gap ms", gap <= 15_000L + cooldown + (HITS + 1) * 1_000L)
        }
    }
}
