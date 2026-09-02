package app.vela.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Sunrise/sunset for the day-night theme (issue #262). Expected times are the published almanac
 * values for each place and date, so this checks the astronomy rather than checking the code
 * against itself. Fixtures are the standard project ones plus the two polar cases, which are the
 * only places the equation has no answer.
 */
class SunTimesTest {

    /** Epoch millis for a UTC instant, so the fixtures do not depend on the JVM's time zone. */
    private fun utc(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0): Long {
        val c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.clear(); c.set(y, mo - 1, d, h, mi, 0)
        return c.timeInMillis
    }

    private fun minutesUtc(millis: Long): Int {
        val c = java.util.Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        c.timeInMillis = millis
        return c.get(java.util.Calendar.HOUR_OF_DAY) * 60 + c.get(java.util.Calendar.MINUTE)
    }

    // Davis, CA (the project's standard fixture). 2026-06-21, the solstice: sunrise 05:39 PDT
    // (12:39 UTC), sunset 20:32 PDT (03:32 UTC the next day).
    @Test fun `midsummer sunrise and sunset at the fixture latitude`() {
        val (rise, set) = SunTimes.riseSet(38.5449, -121.7405, utc(2026, 6, 21, 20))!!
        assertEquals((12 * 60 + 39).toDouble(), minutesUtc(rise).toDouble(), 3.0)
        assertEquals((3 * 60 + 32).toDouble(), minutesUtc(set).toDouble(), 3.0)
    }

    // Same place at the winter solstice: a much shorter day (sunrise 07:22, sunset 16:50 PST).
    @Test fun `midwinter days are shorter at the same place`() {
        val (rise, set) = SunTimes.riseSet(38.5449, -121.7405, utc(2026, 12, 21, 20))!!
        val lengthMin = (set - rise) / 60000.0
        assertTrue("winter day should be about 9.5 h, got ${lengthMin / 60} h", lengthMin in 555.0..585.0)
        val (jRise, jSet) = SunTimes.riseSet(38.5449, -121.7405, utc(2026, 6, 21, 20))!!
        assertTrue("summer day must be longer than winter", (jSet - jRise) > (set - rise))
    }

    @Test fun `evening is night in december and day in june at the same clock time`() {
        // 6 pm local (02:00 UTC next day) - the whole reason the theme cannot key off the clock.
        assertTrue(SunTimes.isNight(38.5449, -121.7405, utc(2026, 12, 22, 2)))
        assertFalse(SunTimes.isNight(38.5449, -121.7405, utc(2026, 6, 22, 2)))
    }

    @Test fun `midday is day and the small hours are night`() {
        assertFalse(SunTimes.isNight(38.5449, -121.7405, utc(2026, 6, 21, 20))) // 1 pm local
        assertTrue(SunTimes.isNight(38.5449, -121.7405, utc(2026, 6, 21, 10))) // 3 am local
    }

    // The equator: roughly twelve hours of daylight whatever the date.
    @Test fun `equatorial day length is near twelve hours year round`() {
        for (month in listOf(1, 4, 7, 10)) {
            val (rise, set) = SunTimes.riseSet(0.0, 0.0, utc(2026, month, 15, 12))!!
            assertEquals("month $month", 720.0, (set - rise) / 60000.0, 15.0)
        }
    }

    // Above the Arctic circle the sun does not cross the horizon at the solstices, so there IS no
    // sunrise - and the answer still has to be day or night, not a default.
    @Test fun `polar summer has no sunrise and reads as day`() {
        assertNull(SunTimes.riseSet(78.2, 15.6, utc(2026, 6, 21, 12))) // Svalbard, midnight sun
        assertFalse(SunTimes.isNight(78.2, 15.6, utc(2026, 6, 21, 0)))
        assertFalse(SunTimes.isNight(78.2, 15.6, utc(2026, 6, 21, 12)))
    }

    @Test fun `polar winter has no sunset and reads as night`() {
        assertNull(SunTimes.riseSet(78.2, 15.6, utc(2026, 12, 21, 12)))
        assertTrue(SunTimes.isNight(78.2, 15.6, utc(2026, 12, 21, 12)))
    }

    // Southern hemisphere: the seasons invert, so a December evening is still light.
    @Test fun `southern hemisphere seasons invert`() {
        val summer = SunTimes.riseSet(-33.87, 151.21, utc(2026, 12, 21, 2))!! // Sydney
        val winter = SunTimes.riseSet(-33.87, 151.21, utc(2026, 6, 21, 2))!!
        assertTrue(
            "December must be the long day in the south",
            (summer.second - summer.first) > (winter.second - winter.first),
        )
    }

    @Test fun `an ordinary place always has a rise and set`() {
        for (month in 1..12) assertNotNull(SunTimes.riseSet(51.5, -0.13, utc(2026, month, 15, 12)))
    }

    @Test fun `the no-location clock fallback splits day from night`() {
        assertTrue(SunTimes.isNightByClock(3))
        assertTrue(SunTimes.isNightByClock(23))
        assertFalse(SunTimes.isNightByClock(12))
        assertFalse(SunTimes.isNightByClock(7))
        assertTrue(SunTimes.isNightByClock(19))
    }
}
