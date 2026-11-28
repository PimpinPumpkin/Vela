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
import app.vela.core.model.RouteSource
import app.vela.core.model.distanceTo
import app.vela.core.voice.VoiceGuide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * A drive on a stand-in (a route planned on the phone while the network was out) whose re-check
 * brings Google's route starting back the way the car came: it is neither offered as a faster
 * route nor taken, and asked again it goes the car's way and replaces the stand-in without a
 * word. Davis fixture.
 */
class StandInTurnRoundTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun at(eastM: Double, northM: Double) = LatLng(lat + northM / 111_320.0, lng0 + eastM / mPerDegLng)

    private val dest = at(3000.0, 600.0)

    private fun route(poly: List<LatLng>, durS: Double, trafficS: Double?, source: RouteSource): Route {
        val d = poly.zipWithNext { a, b -> a.distanceTo(b) }.sum()
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head out", poly.first(), d, durS),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        return Route(poly, listOf(RouteLeg(d, durS, trafficS, ms)), d, durS, trafficS, source = source)
    }

    /** East along y = 0 to x = 3000, then north to [dest]: the phone's own route. */
    private fun standIn() = route((0..300).map { at(it * 10.0, 0.0) } + (1..60).map { at(3000.0, it * 10.0) }, 600.0, null, RouteSource.OBF)

    /** Google's route from [from]: [firstM] east (negative: west, back the way the car came),
     *  north to y = 400, east to x = 3000, north to [dest]. */
    private fun google(from: LatLng, firstM: Double): Route {
        val fx = (from.lng - lng0) * mPerDegLng
        val x1 = fx + firstM
        val first = (0..kotlin.math.abs(firstM).toInt() step 10).map { at(fx + Math.signum(firstM) * it, 0.0) }
        val up = (1..40).map { at(x1, it * 10.0) }
        val east = (1..((3000.0 - x1) / 10).toInt()).map { at(x1 + it * 10.0, 400.0) }
        val last = (1..20).map { at(3000.0, 400.0 + it * 10.0) }
        return route(first + up + east + last, 300.0, 300.0, RouteSource.GOOGLE_HYBRID)
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

    @Test fun `Google's route that starts behind the car waits, then replaces the stand-in silently`() {
        NavStringsRegistry.setLocale(Locale.ENGLISH)
        val asks = AtomicInteger()
        val answers = java.util.Collections.synchronizedList(ArrayList<Route>())
        val ctx = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = NoPrefs
            override fun getSystemService(name: String): Any? = null
            override fun getApplicationContext(): Context = this
        }
        // The first re-check's answer sets off 60 m back west; the second goes on east.
        val data = java.lang.reflect.Proxy.newProxyInstance(MapDataSource::class.java.classLoader, arrayOf(MapDataSource::class.java)) { _, m, args ->
            if (m.name != "directions") throw AssertionError("unexpected fetch: ${m.name}")
            val r = google(args[0] as LatLng, if (asks.incrementAndGet() == 1) -60.0 else 100.0)
            answers += r
            listOf(r)
        } as MapDataSource
        val s = NavSession(data, VoiceGuide(ctx).apply { muted = true }, Haptics(ctx), DiagLog(ctx)).apply { replayMode = false }
        val notes = java.util.Collections.synchronizedList(ArrayList<String>())
        s.onNote = { notes += it }
        val stand = standIn()
        s.start(stand, dest, "Davis Depot")
        // The JVM's SystemClock reads 0: put the last re-check in the past so the next fix asks.
        val lastRecheck = NavSession::class.java.getDeclaredField("lastRecheckMs").apply { isAccessible = true }
        lastRecheck.setLong(s, -10_000_000L)
        var x = 0.0
        fun driveUntil(done: () -> Boolean) {
            val end = System.currentTimeMillis() + 4000
            while (!done() && System.currentTimeMillis() < end) {
                x += 10.0; s.onLocation(at(x, 0.0), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 90.0); Thread.sleep(100)
            }
        }
        driveUntil { notes.any { "turns the car round" in it } }
        assertEquals("$notes", 1, asks.get())
        assertTrue("$notes", notes.any { "turns the car round from here, asking again" in it })
        assertNull("not offered as a faster route", s.state.value.fasterRoute)
        assertTrue("the stand-in is still driven", s.state.value.route === stand)
        // Asked again further on, Google's route goes the car's way and takes over.
        lastRecheck.setLong(s, -10_000_000L)
        driveUntil { s.state.value.route !== stand }
        assertEquals("$notes", 2, asks.get())
        assertTrue("$notes", notes.any { "replaced the stand-in" in it })
        assertTrue(s.state.value.route === answers[1])
        assertNull(s.state.value.fasterRoute)
        s.stop()
    }
    @Test fun `a Google route that keeps turning the car round is asked about a few times, never offered`() {
        NavStringsRegistry.setLocale(Locale.ENGLISH)
        val asks = AtomicInteger()
        val ctx = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = NoPrefs
            override fun getSystemService(name: String): Any? = null
            override fun getApplicationContext(): Context = this
        }
        val data = java.lang.reflect.Proxy.newProxyInstance(MapDataSource::class.java.classLoader, arrayOf(MapDataSource::class.java)) { _, m, args ->
            if (m.name != "directions") throw AssertionError("unexpected fetch: ${m.name}")
            asks.incrementAndGet()
            listOf(google(args[0] as LatLng, -60.0))
        } as MapDataSource
        val s = NavSession(data, VoiceGuide(ctx).apply { muted = true }, Haptics(ctx), DiagLog(ctx)).apply { replayMode = false }
        val notes = java.util.Collections.synchronizedList(ArrayList<String>())
        s.onNote = { notes += it }
        val stand = standIn()
        s.start(stand, dest, "Davis Depot")
        val lastRecheck = NavSession::class.java.getDeclaredField("lastRecheckMs").apply { isAccessible = true }
        var x = 0.0
        repeat(NavSession.STAND_IN_TURN_WAITS + 2) { k ->
            lastRecheck.setLong(s, -10_000_000L)
            val end = System.currentTimeMillis() + 3000
            while (asks.get() <= k && System.currentTimeMillis() < end) {
                x += 10.0; s.onLocation(at(x, 0.0), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 90.0); Thread.sleep(50)
            }
            val settle = System.currentTimeMillis() + 2000
            while (notes.count { it.startsWith("recheck:") } <= k && System.currentTimeMillis() < settle) Thread.sleep(20)
        }
        val rechecks = notes.filter { it.startsWith("recheck:") }
        assertEquals("$rechecks", NavSession.STAND_IN_TURN_WAITS + 2, rechecks.size)
        assertEquals("$rechecks", NavSession.STAND_IN_TURN_WAITS, rechecks.count { "asking again past it" in it })
        assertTrue("$rechecks", rechecks.drop(NavSession.STAND_IN_TURN_WAITS).all { "turns the car round here" in it })
        assertNull(s.state.value.fasterRoute)
        assertTrue(s.state.value.route === stand)
        s.stop()
    }
}
