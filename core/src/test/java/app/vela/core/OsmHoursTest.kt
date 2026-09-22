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
        // Seasonal rules and free text describe no ordinary week; the caller shows the text.
        assertNull(OsmHours.toDayLines("Jan-Mar Mo-Fr 08:00-17:00"))
        assertNull(OsmHours.toDayLines("\"by appointment\""))
        assertNull(OsmHours.toDayLines("variable"))
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

    private fun day(spec: String, i: Int) = OsmHours.toDayLines(spec)!![i]

    @Test fun `the common shapes`() {
        val l = OsmHours.toDayLines("Mo-Fr 06:30-22:00; Sa 08:00-22:00; Su 08:00-21:00")!!
        assertEquals("Monday: 6:30 AM–10 PM", l[0])
        assertEquals("Sunday: 8 AM–9 PM", l[6])
        assertEquals("Monday: Open 24 hours", day("24/7", 0))
        assertEquals("Sunday: Closed", day("Mo-Sa 09:00-17:00; Su off", 6))
    }

    @Test fun `comma separated rule groups and spaced day lists`() {
        assertEquals("Friday: 9 AM–6 PM", day("Mo-Th 09:00-17:00, Fr 09:00-18:00", 4))
        assertEquals("Friday: 9 AM–6 PM", day("Mo-Th 09:00-17:00,Fr 09:00-18:00", 4))
        assertEquals("Sunday: 11 AM–9 PM", day("Mo-Th, Su 11:00-21:00; Fr, Sa 11:00-22:00", 6))
        assertEquals("Saturday: 11 AM–10 PM", day("Mo-Th, Su 11:00-21:00; Fr, Sa 11:00-22:00", 5))
        assertEquals("Friday: 8 AM–12 PM, 1 PM–5:30 PM", day("Mo-Th 08:00-12:00,13:00-18:00, Fr 08:00-12:00,13:00-17:30", 4))
        assertEquals("Thursday: 7 AM–3 AM", day("Su-We 07:00-23:00, Th 07:00-03:00, Fr-Sa 07:00-04:00", 3))
    }

    @Test fun `holidays and dates do not break the week`() {
        assertEquals("Sunday: Closed", day("Mo-Fr 11:00-19:00; Sa 10:00-15:00; Su off; PH off", 6))
        assertEquals("Monday: 5 AM–12 AM", day("PH,Mo-Su 05:00-24:00", 0))
        assertEquals("Monday: Open 24 hours", day("Mo-Su,PH 00:00-00:00", 0))
        assertEquals("Monday: Open 24 hours", day("Mo-Su,PH 00:00+", 0))
        assertEquals("Monday: 7:30 AM–5 PM", day("Mo-Fr,PH 07:30-17:00; Jan 01 off; Dec 25 off; Nov Th[4] off", 0))
    }

    @Test fun `sun events read as words`() {
        assertEquals("Monday: Sunrise–Sunset", day("sunrise-sunset", 0))
        assertEquals("Monday: 7 AM–Dusk", day("07:00-dusk", 0))
        assertEquals("Monday: Sunrise–Sunset", day("(sunrise-00:30)-(sunset+00:30)", 0))
    }

    @Test fun `notes stay notes`() {
        assertNull(OsmHours.toDayLines("\"by appointment\""))
        assertEquals(listOf("By appointment"), OsmHours.lines("\"by appointment\""))
        assertEquals(emptyList<String>(), OsmHours.lines(null))
        val stored = listOf("Monday: 8 AM–5 PM", "Tuesday: Closed")
        assertEquals(stored, OsmHours.lines(stored.joinToString("\n")))
    }

    @Test fun `converted lines compute open and closed`() {
        val l = OsmHours.lines("Mo-Fr 06:30-22:00; Sa 08:00-22:00; Su 08:00-21:00")
        // 2026-09-21 is a Monday.
        assertEquals(true, OpeningHours.statusAt(l, LocalDateTime.of(2026, 9, 21, 12, 0))?.open)
        assertEquals(false, OpeningHours.statusAt(l, LocalDateTime.of(2026, 9, 21, 23, 0))?.open)
        assertEquals(false, OpeningHours.statusAt(l, LocalDateTime.of(2026, 9, 27, 7, 0))?.open) // Sunday 7 AM
    }
}
