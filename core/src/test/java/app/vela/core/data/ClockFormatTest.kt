package app.vela.core.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockFormatTest {
    @After fun reset() { ClockFormat.use24h = false }

    @Test fun twelveHourClockLeavesTextAlone() {
        ClockFormat.use24h = false
        assertEquals("4:35 PM", ClockFormat.show("4:35 PM"))
    }

    @Test fun twentyFourHourClockConverts() {
        ClockFormat.use24h = true
        assertEquals("16:35", ClockFormat.show("4:35 PM"))
        assertEquals("04:35", ClockFormat.show("4:35 AM"))
        assertEquals("00:05", ClockFormat.show("12:05 AM"))
        assertEquals("12:48", ClockFormat.show("12:48\u202FPM"))
        assertEquals("23:59", ClockFormat.show("11:59pm"))
    }

    @Test fun otherTextIsUnchanged() {
        ClockFormat.use24h = true
        assertEquals("16:35", ClockFormat.show("16:35"))
        assertEquals("every 20 min", ClockFormat.show("every 20 min"))
        assertEquals(null, ClockFormat.show(null))
    }
}
