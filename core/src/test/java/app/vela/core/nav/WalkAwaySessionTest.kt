package app.vela.core.nav

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.vela.core.data.MapDataSource
import app.vela.core.diag.DiagLog
import app.vela.core.feedback.Haptics
import app.vela.core.i18n.NavStringsRegistry
import app.vela.core.model.LatLng
import app.vela.core.model.Maneuver
import app.vela.core.model.ManeuverType
import app.vela.core.model.Route
import app.vela.core.model.RouteLeg
import app.vela.core.voice.VoiceGuide
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * Parking short of the destination and walking off ends the drive as arrived ([WalkAwayArrival]),
 * through a whole session: drive a straight route, stop 120 m short, stand, then step.
 */
class WalkAwaySessionTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))

    private fun east(m: Double) = LatLng(lat, lng0 + m / mPerDegLng)

    /** East 2000 m with no turn: depart, then arrive. */
    private fun route(): Route {
        val poly = (0..200).map { east(it * 10.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east on Covell Boulevard", poly.first(), 2000.0, 200.0),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        return Route(poly, listOf(RouteLeg(2000.0, 200.0, null, ms)), 2000.0, 200.0, null)
    }

    /** No preference set: every read gets its default. */
    private object NoPrefs : SharedPreferences {
        override fun getAll(): MutableMap<String, *> = mutableMapOf<String, Any>()
        override fun getString(key: String?, defValue: String?) = defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?) = defValues
        override fun getInt(key: String?, defValue: Int) = defValue
        override fun getLong(key: String?, defValue: Long) = defValue
        override fun getFloat(key: String?, defValue: Float) = defValue
        override fun getBoolean(key: String?, defValue: Boolean) = defValue
        override fun contains(key: String?) = false
        override fun edit(): SharedPreferences.Editor = throw UnsupportedOperationException()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    }

    private fun session(): NavSession {
        val ctx = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = NoPrefs
            override fun getSystemService(name: String): Any? = null
            override fun getApplicationContext(): Context = this
        }
        // Nothing here may fetch: the drive stays on its route and rechecks are off.
        val data = java.lang.reflect.Proxy.newProxyInstance(
            MapDataSource::class.java.classLoader, arrayOf(MapDataSource::class.java),
        ) { _, m, _ -> throw AssertionError("no fetch expected: ${m.name}") } as MapDataSource
        val voice = VoiceGuide(ctx).apply { muted = true }
        return NavSession(data, voice, Haptics(ctx), DiagLog(ctx)).apply {
            liveRechecks = false
            replayMode = false
        }
    }

    private var now = 0.0

    /** Drive east to [toM], a fix a second at 10 m/s, then stand there [standS] seconds. */
    private fun driveAndPark(s: NavSession, toM: Double, standS: Int) {
        s.walkClockS = { now }
        var m = 0.0
        while (m <= toM) { s.onLocation(east(m), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 90.0); m += 10.0; now += 1.0 }
        repeat(standS) { s.onLocation(east(toM), speedMps = 0.0, accuracyM = 5.0, bearingDeg = 90.0); now += 1.0 }
    }

    private fun steps(s: NavSession, n: Int) = repeat(n) { s.onWalkStep(now); now += 0.55 }

    @Test fun `parked 120 m short and walking off arrives`() {
        NavStringsRegistry.setLocale(Locale.ENGLISH)
        val s = session()
        s.start(route(), east(2000.0), "Davis Depot")
        driveAndPark(s, 1880.0, 20)
        assertFalse("standing 120 m short is not arriving by itself", s.state.value.arrived)
        assertTrue(s.walkWatch.value)
        steps(s, WalkAwayArrival.STEPS)
        assertTrue(s.state.value.arrived)
        assertFalse(s.state.value.navigating)
        assertFalse(s.walkWatch.value)
        s.stop()
    }

    @Test fun `a red light short of the destination is not parking`() {
        NavStringsRegistry.setLocale(Locale.ENGLISH)
        val s = session()
        s.start(route(), east(2000.0), "Davis Depot")
        driveAndPark(s, 1880.0, 8)
        assertFalse(s.walkWatch.value)
        steps(s, 40)
        assertFalse(s.state.value.arrived)
        s.stop()
    }

    @Test fun `parked far from the destination keeps guiding`() {
        NavStringsRegistry.setLocale(Locale.ENGLISH)
        val s = session()
        s.start(route(), east(2000.0), "Davis Depot")
        driveAndPark(s, 1500.0, 30)
        assertFalse(s.walkWatch.value)
        steps(s, 40)
        assertTrue(s.state.value.navigating)
        s.stop()
    }
}
