package app.vela.core

import app.vela.core.util.OpeningHours
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class OpeningHoursTest {
    // A store open past midnight (6 AM–1 AM every day) — the reported Safeway case.
    private val lateStore = listOf(
        "Monday: 6 AM–1 AM", "Tuesday: 6 AM–1 AM", "Wednesday: 6 AM–1 AM",
        "Thursday: 6 AM–1 AM", "Friday: 6 AM–1 AM", "Saturday: 6 AM–1 AM", "Sunday: 6 AM–1 AM",
    )

    // 2024-01-01 is a Monday, so these are Monday-at-HH:MM.
    private fun mon(h: Int, m: Int = 0) = LocalDateTime.of(2024, 1, 1, h, m)

    @Test fun openLateEveningBeforeMidnight() {
        assertEquals(OpeningHours.Status(true, "Closes 1 AM"), OpeningHours.statusAt(lateStore, mon(23)))
    }

    @Test fun stillOpenAfterMidnight() { // 12:30 AM — open via YESTERDAY's interval running past midnight
        assertEquals(true, OpeningHours.statusAt(lateStore, mon(0, 30))?.open)
    }

    @Test fun closedInTheEarlyMorningGap() { // 3 AM — after the 1 AM close, before the 6 AM open
        val s = OpeningHours.statusAt(lateStore, mon(3))
        assertEquals(false, s?.open)
        assertEquals("Opens 6 AM", s?.detail)
    }

    @Test fun openInTheAfternoon() {
        assertEquals(true, OpeningHours.statusAt(lateStore, mon(14))?.open)
    }

    @Test fun open24Hours() {
        assertEquals(true, OpeningHours.statusAt(listOf("Monday: Open 24 hours"), mon(3))?.open)
    }

    @Test fun closedForTheDay() {
        val s = OpeningHours.statusAt(listOf("Monday: Closed", "Tuesday: 9 AM–5 PM"), mon(12))
        assertEquals(false, s?.open)
    }

    @Test fun impliedMeridianSecondTimeOnly() { // "5–10 PM" → 5 PM inherits PM
        assertEquals(true, OpeningHours.statusAt(listOf("Monday: 5–10 PM"), mon(18))?.open)
        assertEquals(false, OpeningHours.statusAt(listOf("Monday: 5–10 PM"), mon(11))?.open)
    }

    @Test fun midnightClose() { // "8 AM–12 AM" (closes at midnight) — open at 11 PM
        assertEquals(true, OpeningHours.statusAt(listOf("Monday: 8 AM–12 AM"), mon(23))?.open)
    }

    @Test fun normalHoursClosedAtNight() {
        val s = OpeningHours.statusAt(listOf("Monday: 9 AM–5 PM", "Tuesday: 9 AM–5 PM"), mon(20))
        assertEquals(false, s?.open)
    }

    @Test fun unparseableFallsBackToNull() {
        assertNull(OpeningHours.statusAt(listOf("Monday: whenever we feel like it"), mon(12)))
        assertNull(OpeningHours.statusAt(emptyList(), mon(12)))
    }
}
