package app.vela.core.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class ClockFormatTest {
    @After fun reset() {
        ClockFormat.use24h = false
        ClockFormat.locale = java.util.Locale.US
    }

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

    @Test fun googlesEnglishTimeIsShownInTheAppsLanguage() {
        ClockFormat.locale = java.util.Locale.forLanguageTag("zh-TW")
        assertEquals("下午4:35", ClockFormat.show("4:35 PM"))
        ClockFormat.use24h = true
        assertEquals("16:35", ClockFormat.show("4:35 PM"))
    }

    @Test fun aTransitTimeIsWrittenInTheAppsLanguage() {
        val zone = java.time.ZoneId.of("America/Los_Angeles")
        val epoch = java.time.ZonedDateTime.of(2026, 11, 24, 16, 35, 0, 0, zone).toEpochSecond()
        assertEquals("4:35 PM", ClockFormat.at(epoch, zone))
        ClockFormat.locale = java.util.Locale.KOREA
        assertEquals("오후 4:35", ClockFormat.at(epoch, zone))
    }

    @Test fun aDateGoesBeforeTheTime() {
        assertEquals("MMM d, h:mm a", ClockFormat.pattern(false, java.util.Locale.US, "MMMd"))
        assertEquals("MMM d, y, HH:mm", ClockFormat.pattern(true, java.util.Locale.US, "yMMMd"))
    }

    @Test fun aFlexibleOrLongDayPeriodBecomesThePlainOne() {
        assertEquals("ah:mm", ClockFormat.plainDayPeriod("Bh:mm"))
        assertEquals("h:mm a", ClockFormat.plainDayPeriod("h:mm b"))
        assertEquals("h:mm a", ClockFormat.plainDayPeriod("h:mm aaaa"))
        assertEquals("H 'ba' mm", ClockFormat.plainDayPeriod("H 'ba' mm"))
    }
}
