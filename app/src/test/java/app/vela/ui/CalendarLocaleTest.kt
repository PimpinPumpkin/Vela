package app.vela.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.temporal.WeekFields
import java.util.Locale

/** The locale the date picker is drawn in ([AppLocale.calendarLocale]): app language, device region. */
class CalendarLocaleTest {
    private fun tag(s: String) = Locale.forLanguageTag(s)
    private fun firstDay(l: Locale) = WeekFields.of(l).firstDayOfWeek

    @Test fun `following the system keeps the device locale whole`() {
        val device = tag("en-US-u-fw-mon")
        assertEquals(device, AppLocale.calendarLocale("", device))
    }

    @Test fun `a picked language takes the device region`() {
        val l = AppLocale.calendarLocale("en", tag("de-DE"))
        assertEquals("en-DE", l.toLanguageTag())
        assertEquals(DayOfWeek.MONDAY, firstDay(l))
    }

    @Test fun `a US phone in English still starts on Sunday`() {
        val l = AppLocale.calendarLocale("en", tag("en-US"))
        assertEquals("en-US", l.toLanguageTag())
        assertEquals(DayOfWeek.SUNDAY, firstDay(l))
    }

    @Test fun `the device first day of week preference comes along`() {
        val l = AppLocale.calendarLocale("fr", tag("en-US-u-fw-mon"))
        assertEquals("fr-US-u-fw-mon", l.toLanguageTag())
        assertEquals(DayOfWeek.MONDAY, firstDay(l))
    }

    @Test fun `a language with its own region keeps it and takes the device region as rg`() {
        val l = AppLocale.calendarLocale("zh-TW", tag("de-DE"))
        assertEquals("TW", l.country)
        assertEquals("dezzzz", l.getUnicodeLocaleType("rg"))
        assertEquals(DayOfWeek.MONDAY, firstDay(l))
        assertNull(AppLocale.calendarLocale("pt-BR", tag("pt-BR")).getUnicodeLocaleType("rg"))
    }

    @Test fun `device digits stay with the device language`() {
        val l = AppLocale.calendarLocale("en", tag("ar-EG-u-nu-arab"))
        assertEquals("en-EG", l.toLanguageTag())
    }

    @Test fun `a device with no region leaves the language alone`() {
        assertEquals("fr", AppLocale.calendarLocale("fr", tag("en")).toLanguageTag())
    }
}
