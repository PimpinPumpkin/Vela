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
import java.util.concurrent.atomic.AtomicInteger

/**
 * A paused drive stays paused (SPEC 4.6, Pause): a reroute asked just before the tap does not
 * land a route under it, and driving across the route does not resume it. Davis fixture.
 */
class PauseSessionTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun at(eastM: Double, northM: Double) = LatLng(lat + northM / 111_320.0, lng0 + eastM / mPerDegLng)
    private val dest = at(2000.0, 0.0)

    /** East 2 km along one street. */
    private fun planned(): Route {
        val poly = (0..200).map { at(it * 10.0, 0.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east on Covell Boulevard", poly.first(), 2000.0, 200.0, road = "Covell Boulevard"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        return Route(poly, listOf(RouteLeg(2000.0, 200.0, null, ms)), 2000.0, 200.0, null)
    }

    /** From [from] east, then south to the street and on to [dest]. */
    private fun answer(from: LatLng): Route {
        val fy = (from.lat - lat) * 111_320.0
        val fx = (from.lng - lng0) * mPerDegLng
        val poly = listOf(from, at(fx + 300.0, fy), at(fx + 300.0, 0.0), dest)
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east", from, 300.0, 30.0),
            Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto Oak Avenue", poly[1], fy, fy / 10.0, road = "Oak Avenue"),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Covell Boulevard", poly[2], 2000.0 - fx - 300.0, 100.0, road = "Covell Boulevard"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", dest, 0.0, 0.0),
        )
        val d = ms.sumOf { it.distanceMeters }
        return Route(poly, listOf(RouteLeg(d, d / 10.0, null, ms)), d, d / 10.0, null)
    }

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

    private fun session(asks: AtomicInteger, answerDelayMs: Long): NavSession {
        val ctx = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = NoPrefs
            override fun getSystemService(name: String): Any? = null
            override fun getApplicationContext(): Context = this
        }
        val data = java.lang.reflect.Proxy.newProxyInstance(MapDataSource::class.java.classLoader, arrayOf(MapDataSource::class.java)) { _, m, args ->
            if (m.name != "directions") throw AssertionError("unexpected fetch: ${m.name}")
            asks.incrementAndGet()
            Thread.sleep(answerDelayMs)
            listOf(answer(args[0] as LatLng))
        } as MapDataSource
        return NavSession(data, VoiceGuide(ctx).apply { muted = true }, Haptics(ctx), DiagLog(ctx)).apply { liveRechecks = false; replayMode = false }
    }

    /** The JVM clock reads 0: put the last adoption in the past so the reroute cooldown passes. */
    private fun clearCooldown(s: NavSession) =
        NavSession::class.java.getDeclaredField("lastRerouteAdoptMs").apply { isAccessible = true }.setLong(s, -NavSession.REROUTE_COOLDOWN_MS * 10)

    @Test fun `a reroute asked just before pausing does not land and resume the drive`() {
        NavStringsRegistry.setLocale(Locale.ENGLISH)
        val asks = AtomicInteger()
        val s = session(asks, answerDelayMs = 800)
        val notes = java.util.Collections.synchronizedList(ArrayList<String>())
        s.onNote = { notes += it }
        val plan = planned()
        s.start(plan, dest, "Davis Depot")
        clearCooldown(s)
        for (x in 0..20) s.onLocation(at(x * 10.0, 0.0), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 90.0)
        // Off to the north until the reroute is asked, then pause while it is in flight.
        var y = 0.0
        while (asks.get() == 0 && y < 300.0) { y += 10.0; s.onLocation(at(200.0, y), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 0.0); Thread.sleep(50) }
        assertTrue("$notes", asks.get() == 1)
        s.setPaused(true)
        // Drive on east, parallel to the street and well off it, as the rerouted line would go.
        for (k in 1..10) { s.onLocation(at(200.0 + k * 10.0, y), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 90.0); Thread.sleep(150) }
        assertTrue("$notes", s.state.value.route === plan)
        assertTrue("$notes", s.state.value.paused)
        s.stop()
    }

    @Test fun `driving across the route does not resume a paused drive, driving along it does`() {
        NavStringsRegistry.setLocale(Locale.ENGLISH)
        val asks = AtomicInteger()
        val s = session(asks, answerDelayMs = 0)
        val notes = java.util.Collections.synchronizedList(ArrayList<String>())
        s.onNote = { notes += it }
        s.start(planned(), dest, "Davis Depot")
        for (x in 0..20) s.onLocation(at(x * 10.0, 0.0), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 90.0)
        s.setPaused(true)
        // Off the street to the north (arms the auto-resume), then straight across it to the south.
        for (k in 1..8) s.onLocation(at(260.0, 80.0 - k * 10.0), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 180.0)
        for (k in 0..8) s.onLocation(at(260.0, -k * 10.0), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 180.0)
        assertTrue("$notes", s.state.value.paused)
        // Back onto it and along it, east.
        for (k in 0..5) s.onLocation(at(400.0 + k * 10.0, 0.0), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 90.0)
        assertFalse("$notes", s.state.value.paused)
        s.stop()
    }

    @Test fun `a drive started while already rolling along its route says to continue on its road`() {
        NavStringsRegistry.setLocale(Locale.ENGLISH)
        val s = session(AtomicInteger(), answerDelayMs = 0)
        val plan = planned()
        assertTrue(s.rollingOpener(plan, at(100.0, 3.0), 92.0, false) == "Continue on Covell Boulevard")
        // Going the other way, standing still, or away from the line: the usual opener.
        assertTrue(s.rollingOpener(plan, at(100.0, 3.0), 270.0, false) == null)
        assertTrue(s.rollingOpener(plan, at(100.0, 3.0), null, false) == null)
        assertTrue(s.rollingOpener(plan, at(100.0, 120.0), 90.0, false) == null)
        // A departure that names no road: nothing at the start.
        val bare = plan.copy(legs = plan.legs.map { leg -> leg.copy(maneuvers = leg.maneuvers.mapIndexed { i, m -> if (i == 0) m.copy(road = null, instruction = "Start your route") else m }) })
        assertTrue(s.rollingOpener(bare, at(100.0, 3.0), 90.0, false) == "")
    }
}
