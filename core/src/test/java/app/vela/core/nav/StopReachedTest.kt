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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

/**
 * The stop-reached callback ([NavSession.onStopReached]) that puts a card on screen as each stop
 * is reached: a whole session driven along a straight route with stops, the voice muted, so the
 * card is the only thing that shows it.
 */
class StopReachedTest {
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
            replayMode = true
        }
    }

    /** Drive [s] from the start to the end of [r], a fix every 10 m at 10 m/s. */
    private fun drive(s: NavSession, r: Route) {
        var m = 0.0
        while (m <= 2000.0 && !s.state.value.arrived) {
            s.onLocation(east(m), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 90.0)
            m += 10.0
        }
    }

    @Test fun `each stop is reported once, in order, and never a silent via or the destination`() {
        NavStringsRegistry.setLocale(Locale.ENGLISH)
        val s = session()
        val reached = mutableListOf<String>()
        s.onStopReached = { reached += it }
        val r = route()
        s.start(
            r, east(2000.0), "Davis Depot",
            stops = listOf(
                NavSession.NavStop(east(600.0), "Davis Food Co-op"),
                NavSession.NavStop(east(1000.0), "", silent = true),
                NavSession.NavStop(east(1400.0), "Sacramento Valley Station"),
            ),
        )
        drive(s, r)
        assertTrue("the drive should arrive", s.state.value.arrived)
        assertEquals(listOf("Davis Food Co-op", "Sacramento Valley Station"), reached)
        s.stop()
    }

    @Test fun `a stop with no name is still reported, blank`() {
        NavStringsRegistry.setLocale(Locale.ENGLISH)
        val s = session()
        val reached = mutableListOf<String>()
        s.onStopReached = { reached += it }
        val r = route()
        s.start(r, east(2000.0), "Davis Depot", stops = listOf(NavSession.NavStop(east(800.0), "")))
        drive(s, r)
        assertEquals(listOf(""), reached)
        s.stop()
    }

    @Test fun `a replay running through fixes to reach a moment shows no card`() {
        NavStringsRegistry.setLocale(Locale.ENGLISH)
        val s = session()
        val reached = mutableListOf<String>()
        s.onStopReached = { reached += it }
        val r = route()
        s.start(r, east(2000.0), "Davis Depot", stops = listOf(NavSession.NavStop(east(600.0), "Davis Food Co-op")))
        s.silent = true
        drive(s, r)
        assertTrue(reached.isEmpty())
        s.stop()
    }
}
