package app.vela.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClosingTimeTest {

    @Test fun parsesEnglish12h() {
        assertEquals(21 * 60, ClosingTime.closingMinuteOfDay("Open ⋅ Closes 9 PM", true))
    }

    @Test fun parsesMinutes() {
        assertEquals(19 * 60 + 30, ClosingTime.closingMinuteOfDay("Open ⋅ Closes 7:30 PM", true))
    }

    @Test fun parses24hLocales() {
        assertEquals(19 * 60, ClosingTime.closingMinuteOfDay("Ouvert ⋅ Ferme à 19:00", true))
        assertEquals(19 * 60, ClosingTime.closingMinuteOfDay("Geöffnet ⋅ Schließt um 19:00", true))
    }

    @Test fun closesSoonStillFindsTheTime() {
        assertEquals(21 * 60, ClosingTime.closingMinuteOfDay("Open ⋅ Closes soon ⋅ 9 PM", true))
    }

    @Test fun midnightClosingIsTonight() {
        // 1440, not 0 — must stay later than any same-day arrival in plain arithmetic.
        assertEquals(24 * 60, ClosingTime.closingMinuteOfDay("Open ⋅ Closes 12 AM", true))
        assertEquals(24 * 60, ClosingTime.closingMinuteOfDay("Ouvert ⋅ Ferme à 00:00", true))
    }

    @Test fun noonPmIsNoon() {
        assertEquals(12 * 60, ClosingTime.closingMinuteOfDay("Open ⋅ Closes 12 PM", true))
    }

    @Test fun closedPlaceNeverParses() {
        // A closed status carries an OPENING time; parsing it as a closing is the failure mode.
        assertNull(ClosingTime.closingMinuteOfDay("Closed ⋅ Opens 9 AM", false))
        assertNull(ClosingTime.closingMinuteOfDay("Closed ⋅ Opens 9 AM", null))
    }

    @Test fun open24HoursHasNoClosing() {
        assertNull(ClosingTime.closingMinuteOfDay("Open 24 hours", true))
    }

    @Test fun nullAndBlankStatus() {
        assertNull(ClosingTime.closingMinuteOfDay(null, true))
        assertNull(ClosingTime.closingMinuteOfDay("Open", true))
    }
}
