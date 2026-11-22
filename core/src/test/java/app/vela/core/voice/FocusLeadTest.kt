package app.vela.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusLeadTest {
    /** Holds posted blocks until the test runs them, as the main-thread handler would. */
    private class Clock {
        val waiting = mutableListOf<Pair<Long, () -> Unit>>()
        fun post(ms: Long, block: () -> Unit) { waiting += ms to block }
        fun runAll() { val now = waiting.toList(); waiting.clear(); now.forEach { it.second() } }
    }

    @Test fun `a line with focus already held speaks at once`() {
        val clock = Clock()
        val lead = FocusLead(clock::post)
        var spoke = 0
        lead.run(fresh = false, leadMs = 350) { spoke++ }
        assertEquals(1, spoke)
        assertTrue(clock.waiting.isEmpty())
    }

    @Test fun `a line with fresh focus waits for the lead, then speaks`() {
        val clock = Clock()
        val lead = FocusLead(clock::post)
        var spoke = 0
        lead.run(fresh = true, leadMs = 350) { spoke++ }
        assertEquals(0, spoke)
        assertEquals(350L, clock.waiting.single().first)
        clock.runAll()
        assertEquals(1, spoke)
    }

    @Test fun `muting during the lead drops the waiting line`() {
        val clock = Clock()
        val lead = FocusLead(clock::post)
        var spoke = 0
        lead.run(fresh = true, leadMs = 350) { spoke++ }
        lead.stop()
        clock.runAll()
        assertEquals(0, spoke)
    }

    @Test fun `a line asked after the stop still speaks`() {
        val clock = Clock()
        val lead = FocusLead(clock::post)
        val spoke = mutableListOf<String>()
        lead.run(fresh = true, leadMs = 350) { spoke += "before" }
        lead.stop()
        lead.run(fresh = true, leadMs = 350) { spoke += "after" }
        clock.runAll()
        assertEquals(listOf("after"), spoke)
    }
}
