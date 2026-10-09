package app.vela.ui.place

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** The depart or arrive time kept across the sheet dropping its content ([DepartPick]). */
class DepartPickTest {
    private val day = LocalDate.of(2026, 10, 9)
    private val now = LocalDateTime.of(2026, 10, 9, 12, 0)

    @Test fun `a pick comes back for the same destination while its time is ahead`() {
        DepartPick.keep("via F St", 1, day, LocalTime.of(14, 30))
        val p = DepartPick.restore("via F St", now)!!
        assertEquals(1, p.mode)
        assertEquals(LocalTime.of(14, 30), p.time)
        assertFalse(DepartPick.dropped())
    }

    @Test fun `another destination drops the pick and reports it once`() {
        DepartPick.keep("via F St", 2, day, LocalTime.of(14, 30))
        assertNull(DepartPick.restore("via Covell Blvd", now))
        assertTrue(DepartPick.dropped())
        assertFalse(DepartPick.dropped())
        assertNull(DepartPick.restore("via F St", now))
    }

    @Test fun `a pick whose time has passed is dropped`() {
        DepartPick.keep(null, 1, day, LocalTime.of(11, 0))
        assertNull(DepartPick.restore(null, now))
        assertTrue(DepartPick.dropped())
    }

    @Test fun `leave now keeps nothing, and last available has no time to pass`() {
        DepartPick.keep(null, 0, day, LocalTime.of(14, 0))
        assertNull(DepartPick.restore(null, now))
        assertFalse(DepartPick.dropped())
        DepartPick.keep(null, 3, day, LocalTime.of(1, 0))
        assertEquals(3, DepartPick.restore(null, now)!!.mode)
    }
}
