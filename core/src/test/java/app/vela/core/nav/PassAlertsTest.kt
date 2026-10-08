package app.vela.core.nav

import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PassAlertsTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun east(m: Double) = LatLng(lat, lng0 + m / mPerDegLng)
    private fun place(id: String, m: Double, sound: String = "ping") = PassAlerts.Target(id, "Place $id", east(m), sound)

    /** Drives east from [fromM] to [toM] at [speed] m/s, one fix a second; returns what sounded, in order. */
    private fun drive(t: PassAlerts.Tracker, targets: List<PassAlerts.Target>, fromM: Double, toM: Double, speed: Double, startMs: Long = 0L): List<String> {
        val out = ArrayList<String>()
        var m = fromM; var now = startMs
        while (m <= toM) {
            t.onFix(east(m), speed, now, targets)?.let { out += it.id }
            m += speed; now += 1_000
        }
        return out
    }

    @Test fun `a place sounds once as the car comes up on it`() {
        val t = PassAlerts.Tracker()
        assertEquals(listOf("a"), drive(t, listOf(place("a", 1_000.0)), 0.0, 2_000.0, 15.0))
    }

    @Test fun `places sound in the order they are passed, and one off the road stays quiet`() {
        val t = PassAlerts.Tracker()
        val far = PassAlerts.Target("x", "Far", LatLng(lat + 400.0 / 111_320.0, lng0 + 1_500.0 / mPerDegLng), "ping")
        assertEquals(listOf("a", "b"), drive(t, listOf(place("b", 2_500.0), far, place("a", 800.0)), 0.0, 3_000.0, 15.0))
    }

    @Test fun `the place a drive starts beside is not announced`() {
        val t = PassAlerts.Tracker()
        assertEquals(emptyList<String>(), drive(t, listOf(place("home", 30.0)), 0.0, 1_000.0, 12.0))
    }

    @Test fun `creeping up on a place holds the sound until the car is moving`() {
        val t = PassAlerts.Tracker()
        val targets = listOf(place("a", 500.0))
        t.onFix(east(0.0), 10.0, 0, targets)
        assertNull("in a queue 100 m short", t.onFix(east(400.0), 0.5, 1_000, targets))
        assertEquals("a", t.onFix(east(410.0), 6.0, 2_000, targets)?.id)
        assertNull(t.onFix(east(420.0), 6.0, 3_000, targets))
    }

    @Test fun `a loop past the same place is quiet for a quarter of an hour`() {
        val t = PassAlerts.Tracker()
        val targets = listOf(place("a", 1_000.0))
        assertEquals(listOf("a"), drive(t, targets, 0.0, 2_000.0, 15.0))
        // Back past it two minutes later, then again twenty minutes later.
        assertEquals(emptyList<String>(), drive(t, targets, 0.0, 2_000.0, 15.0, startMs = 120_000))
        assertEquals(listOf("a"), drive(t, targets, 0.0, 2_000.0, 15.0, startMs = 20 * 60_000L))
    }

    @Test fun `wander at the edge of the radius is one alert`() {
        val t = PassAlerts.Tracker()
        val targets = listOf(place("a", 1_000.0))
        t.onFix(east(0.0), 10.0, 0, targets)
        assertEquals("a", t.onFix(east(860.0), 10.0, 1_000, targets)?.id)
        assertNull(t.onFix(east(840.0), 10.0, 2_000, targets)) // 160 m: outside the radius, inside the exit ring
        assertNull(t.onFix(east(870.0), 10.0, 3_000, targets))
    }

    @Test fun `where the drive is going stays quiet, a place before it does not`() {
        val t = PassAlerts.Tracker()
        val targets = listOf(place("on the way", 800.0), place("the stop", 2_000.0))
        val goingTo = listOf(east(2_040.0))
        val heard = ArrayList<String>()
        var m = 0.0; var ms = 0L
        while (m <= 3_000.0) { t.onFix(east(m), 15.0, ms, targets, goingTo)?.let { heard += it.id }; m += 15.0; ms += 1_000 }
        assertEquals(listOf("on the way"), heard)
    }

    @Test fun `every sound key but the name has notes`() {
        PassAlerts.SOUNDS.forEach { s -> assertEquals(s == PassAlerts.NAME, PassAlerts.notes(s) == null) }
    }
}
