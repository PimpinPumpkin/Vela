package app.vela.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalTime
import java.util.Locale

class ClockTimeFormatterTest {
    @Test fun `12-hour clock overrides a 24-hour locale`() {
        val fmt = clockTimeFormatter(false, Locale.UK)
        assertEquals("7:30 PM", LocalTime.of(19, 30).format(fmt).uppercase(Locale.ROOT))
        assertEquals("12:00 AM", LocalTime.MIDNIGHT.format(fmt).uppercase(Locale.ROOT))
        assertEquals("12:00 PM", LocalTime.NOON.format(fmt).uppercase(Locale.ROOT))
    }

    @Test fun `24-hour clock overrides a 12-hour locale`() {
        val fmt = clockTimeFormatter(true, Locale.US)
        assertEquals("19:30", LocalTime.of(19, 30).format(fmt))
        assertEquals("00:00", LocalTime.MIDNIGHT.format(fmt))
        assertEquals("12:00", LocalTime.NOON.format(fmt))
    }

    @Test fun `route departure window uses the selected clock`() {
        val time = LocalTime.of(19, 30)
        val fmt = clockTimeFormatter(false, Locale.forLanguageTag("en-NZ"))
        assertEquals("7:15 PM", time.minusMinutes(15).format(fmt).uppercase(Locale.ROOT))
        assertEquals("7:19 PM", time.minusMinutes(11).format(fmt).uppercase(Locale.ROOT))
    }

    @Test fun `the day period sits where the language puts it`() {
        val t = LocalTime.of(19, 30)
        assertEquals("下午7:30", t.format(clockTimeFormatter(false, Locale.forLanguageTag("zh-TW"))))
        assertEquals("오후 7:30", t.format(clockTimeFormatter(false, Locale.KOREA)))
        assertEquals("19:30", t.format(clockTimeFormatter(true, Locale.forLanguageTag("zh-TW"))))
    }

    @Test fun `a flexible or long day period becomes the plain one`() {
        assertEquals("ah:mm", plainDayPeriod("Bh:mm"))
        assertEquals("h:mm a", plainDayPeriod("h:mm b"))
        assertEquals("h:mm a", plainDayPeriod("h:mm aaaa"))
        assertEquals("H 'ba' mm", plainDayPeriod("H 'ba' mm"))
    }
}
