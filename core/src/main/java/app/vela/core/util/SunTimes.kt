package app.vela.core.util

import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Sunrise and sunset for a place and a moment, computed on the device (issue #262).
 *
 * The day/night map theme needs to know whether it is dark OUTSIDE, which is a question about the
 * sun, not about the clock: 6 pm is night in December and broad daylight in June, and how far apart
 * those are depends entirely on latitude. This is the standard sunrise equation - a closed-form
 * approximation, no network, no API key, no almanac table - accurate to about a minute at ordinary
 * latitudes, which is far inside what a theme flip needs.
 *
 * Everything takes an explicit `nowMillis`, so the tests drive it with fixed instants.
 */
object SunTimes {

    /** Sun altitude at the moment the disc's upper limb touches the horizon, allowing for
     *  refraction - the standard -0.833 degrees, the same figure almanacs publish sunrise from. */
    private const val HORIZON_DEG = -0.833

    private const val DAY_MS = 86_400_000.0
    private const val UNIX_EPOCH_JULIAN = 2440587.5

    /** Sunrise and sunset (epoch millis) for the day containing [nowMillis] at [lat]/[lng], or null
     *  inside the polar circles when the sun does not cross the horizon that day - see [isNight],
     *  which handles that case rather than pretending there is a sunrise. */
    fun riseSet(lat: Double, lng: Double, nowMillis: Long): Pair<Long, Long>? {
        val julian = nowMillis / DAY_MS + UNIX_EPOCH_JULIAN
        // Which day's sunrise, in LOCAL solar terms - shift the instant by the longitude before
        // rounding to a day. Rounding in UTC picks the wrong day either side of midnight UTC, which
        // in the Americas is the evening: 7 pm in California is already "tomorrow" in UTC, so an
        // evening before sunset was compared against the NEXT day's sunrise and read as night.
        val n = floor(julian - 2451545.0 + 0.0008 + 0.5 + lng / 360.0)
        val meanSolarNoon = n - lng / 360.0
        val m = rad((357.5291 + 0.98560028 * meanSolarNoon) % 360.0)
        // Equation of the centre: the correction for Earth's orbit not being a circle.
        val c = 1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)
        val lambda = rad((deg(m) + c + 180.0 + 102.9372) % 360.0)
        val transit = 2451545.0 + meanSolarNoon + 0.0053 * sin(m) - 0.0069 * sin(2 * lambda)
        val declination = asin(sin(lambda) * sin(rad(23.4397)))
        val cosHourAngle =
            (sin(rad(HORIZON_DEG)) - sin(rad(lat)) * sin(declination)) / (cos(rad(lat)) * cos(declination))
        // |cos| > 1 means the sun never reaches the horizon: midnight sun, or polar night.
        if (abs(cosHourAngle) > 1.0) return null
        val hourAngle = deg(acos(cosHourAngle))
        val set = transit + hourAngle / 360.0
        val rise = transit - hourAngle / 360.0
        return julianToMillis(rise) to julianToMillis(set)
    }

    /**
     * Whether it is dark outside at [lat]/[lng] at [nowMillis].
     *
     * Inside the polar circles there may be no sunrise at all that day, and the answer still has to
     * be one thing or the other: the sun's declination against the latitude says which side of the
     * midnight sun you are on, so an Arctic summer reads as day and an Arctic winter as night
     * (rather than defaulting to one of them and being wrong for six months).
     */
    fun isNight(lat: Double, lng: Double, nowMillis: Long): Boolean {
        val rs = riseSet(lat, lng, nowMillis)
        if (rs == null) {
            // Same declination as above; the sun is up all day when it shares the pole's sign.
            val julian = nowMillis / DAY_MS + UNIX_EPOCH_JULIAN
            val n = floor(julian - 2451545.0 + 0.0008 + 0.5 + lng / 360.0)
            val m = rad((357.5291 + 0.98560028 * n) % 360.0)
            val c = 1.9148 * sin(m) + 0.02 * sin(2 * m) + 0.0003 * sin(3 * m)
            val lambda = rad((deg(m) + c + 180.0 + 102.9372) % 360.0)
            val declination = deg(asin(sin(lambda) * sin(rad(23.4397))))
            return (lat >= 0) != (declination >= 0) // pole tilted away from the sun = polar night
        }
        val (rise, set) = rs
        return nowMillis < rise || nowMillis >= set
    }

    /**
     * The day/night answer when there is no location fix yet - a plain local-clock rule.
     *
     * Deliberately crude, and deliberately NOT a stand-in for the real thing: a maps app has a
     * location within seconds of opening, so this only covers the first moments after a cold launch
     * (or a user who denied location entirely). [hourOfDay] is the local hour, 0-23.
     */
    fun isNightByClock(hourOfDay: Int): Boolean = hourOfDay < 7 || hourOfDay >= 19

    private fun julianToMillis(julian: Double): Long = ((julian - UNIX_EPOCH_JULIAN) * DAY_MS).toLong()
    private fun rad(d: Double) = d * Math.PI / 180.0
    private fun deg(r: Double) = r * 180.0 / Math.PI
}
