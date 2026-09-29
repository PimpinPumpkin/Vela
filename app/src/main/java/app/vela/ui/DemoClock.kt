package app.vela.ui

import app.vela.core.model.Place
import app.vela.core.util.OpeningHours
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A pinned clock for screenshots (2026-09-29): `adb shell setprop debug.vela.tune.demoClock 720`
 * (minutes since midnight; unset or 0 = off). Google decides a place's Open/Closed line at request
 * time, so a shot taken at night read "Closed" under a status bar spoofed to noon. With the dial
 * set, the badge is recomputed from the place's OWN hours at that time (nothing invented: it is what
 * the badge would say at noon), the arrival clock counts from the same time, and transit boards
 * and itineraries are fetched FOR that time (the pinned time's next occurrence), so their departures
 * and countdowns agree with the clock. A debug dial, never a setting; it reads a system property
 * only (never the calibration bundle), so it is off on every phone that never set it.
 */
object DemoClock {
    fun minutes(): Int? = AppTune.local("demoClock")?.toInt()?.takeIf { it > 0 }

    /** The pinned time's next occurrence: today while it is still ahead, else tomorrow. One instant
     *  for everything (status badge, arrival clock, transit fetches, countdowns). */
    fun now(): LocalDateTime? = minutes()?.let { m ->
        val t = LocalDate.now().atTime(m / 60, m % 60)
        if (t.isAfter(LocalDateTime.now())) t else t.plusDays(1)
    }

    fun epochSec(): Long? = now()?.atZone(ZoneId.systemDefault())?.toEpochSecond()

    /** The pinned instant, or the real clock when the dial is off. */
    fun nowMs(): Long = epochSec()?.times(1000L) ?: System.currentTimeMillis()

    fun nowSec(): Long = nowMs() / 1000L

    /** The status line and open flag at the pinned time, from the place's hours; null when the dial
     *  is off or the place has no parseable hours. */
    fun status(place: Place): Pair<String, Boolean>? {
        val t = now() ?: return null
        val s = OpeningHours.statusAt(place.hours, t) ?: return null
        return ((if (s.open) "Open" else "Closed") + " · " + s.detail) to s.open
    }
}
