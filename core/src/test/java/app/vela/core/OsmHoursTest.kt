package app.vela.core

import app.vela.core.util.OpeningHours
import app.vela.core.util.OsmHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class OsmHoursTest {
    @Test fun weekdaysAndSaturday() {
        val lines = OsmHours.toDayLines("Mo-Fr 08:00-17:00; Sa 09:00-13:00")!!
        assertEquals("Monday: 8 AM–5 PM", lines[0])
        assertEquals("Saturday: 9 AM–1 PM", lines[5])
        assertEquals("Sunday: Closed", lines[6])
    }

    @Test fun twentyFourSeven() {
        assertEquals(List(7) { "${listOf("Monday","Tuesday","Wednesday","Thursday","Friday","Saturday","Sunday")[it]}: Open 24 hours" }, OsmHours.toDayLines("24/7"))
    }

    @Test fun laterRulesOverrideAndOffCloses() {
        val lines = OsmHours.toDayLines("Mo-Su 06:00-22:00; Su off")!!
        assertEquals("Saturday: 6 AM–10 PM", lines[5])
        assertEquals("Sunday: Closed", lines[6])
    }

    @Test fun splitShiftsAndMinutes() {
        val lines = OsmHours.toDayLines("Mo-Fr 11:30-14:30,17:00-22:00")!!
        assertEquals("Monday: 11:30 AM–2:30 PM, 5 PM–10 PM", lines[0])
    }

    @Test fun everyDayWhenNoDayPart() {
        assertEquals("Wednesday: 8 AM–8 PM", OsmHours.toDayLines("08:00-20:00")!![2])
    }

    @Test fun unknownSyntaxIsNull() {
        assertNull(OsmHours.toDayLines("Mo-Fr 08:00-17:00; PH off"))
        assertNull(OsmHours.toDayLines("sunrise-sunset"))
        assertNull(OsmHours.toDayLines("Jan-Mar Mo-Fr 08:00-17:00"))
    }

    @Test fun theStatusParserReadsTheResult() {
        val lines = OsmHours.toDayLines("Mo-Fr 08:00-17:00; Sa 09:00-13:00")!!
        val monNoon = LocalDateTime.of(2026, 9, 14, 12, 0)
        val st = OpeningHours.statusAt(lines, monNoon)!!
        assertTrue(st.open)
        assertEquals("Closes 5 PM", st.detail)
        val sunNoon = LocalDateTime.of(2026, 9, 13, 12, 0)
        assertEquals(false, OpeningHours.statusAt(lines, sunNoon)!!.open)
    }
}
