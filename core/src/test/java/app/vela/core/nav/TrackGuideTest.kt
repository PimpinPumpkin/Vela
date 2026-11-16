package app.vela.core.nav

import app.vela.core.nav.TrackGuide.Phase
import app.vela.core.nav.TrackGuide.Say
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where a rider stands on a GPX track ride, fix by fix, and what is said. */
class TrackGuideTest {
    /** The lines said for a run of fixes, true = on the line. */
    private fun said(vararg on: Boolean): List<Say?> {
        var s = TrackGuide.State()
        return on.map { o -> TrackGuide.next(s, o).also { s = it.first }.second }
    }

    @Test fun startingOnTheTrackStartsTheRoute() {
        assertEquals(listOf(Say.STARTED_HERE, null, null), said(true, true, true))
    }

    @Test fun startingAwaySaysGoToStartThenStarted() {
        assertEquals(listOf(Say.GO_TO_START, null, null, Say.STARTED, null), said(false, false, false, true, true))
    }

    @Test fun leavingAndRejoiningAreSaidOnce() {
        assertEquals(
            listOf(Say.STARTED_HERE, null, null, Say.LEFT, null, null, Say.FOLLOWING, null),
            said(true, false, false, false, false, true, true, true),
        )
    }

    @Test fun oneStrayFixSaysNothing() {
        assertEquals(listOf(Say.STARTED_HERE, null, null, null, null), said(true, false, true, false, true))
        // Off, then one fix back on and off again: still off, nothing said.
        assertEquals(listOf(Say.STARTED_HERE, null, null, Say.LEFT, null, null), said(true, false, false, false, true, false))
    }

    @Test fun awayIsBeforeTheStartAndAfterLeaving() {
        assertTrue(TrackGuide.away(TrackGuide.State(Phase.TO_START)))
        assertTrue(TrackGuide.away(TrackGuide.State(Phase.OFF)))
        assertTrue(!TrackGuide.away(TrackGuide.State(Phase.ON, hits = 2)))
        assertTrue(!TrackGuide.away(TrackGuide.State()))
    }
}
