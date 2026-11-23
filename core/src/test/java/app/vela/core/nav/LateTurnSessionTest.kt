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
import java.util.concurrent.atomic.AtomicInteger

/**
 * A reroute whose first turn the car cannot make any more (a parking lot's aisle a few meters
 * ahead) is shown at once with that turn unsaid, then asked again once the car is past it and
 * swapped without a word ([NavSession.followLate]). Davis fixture.
 */
class LateTurnSessionTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val mPerDegLng = 111_320.0 * Math.cos(Math.toRadians(lat))
    private fun at(eastM: Double, northM: Double) = LatLng(lat + northM / 111_320.0, lng0 + eastM / mPerDegLng)

    private val dest = at(2000.0, 600.0)

    /** East along the street to [dest]'s longitude, then north to it. */
    private fun planned(): Route {
        val poly = (0..200).map { at(it * 10.0, 0.0) } + (1..60).map { at(2000.0, it * 10.0) }
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head east on Covell Boulevard", poly.first(), 2000.0, 200.0, road = "Covell Boulevard"),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Oak Avenue", at(2000.0, 0.0), 600.0, 60.0, road = "Oak Avenue"),
            Maneuver(ManeuverType.ARRIVE, "Arrive", poly.last(), 0.0, 0.0),
        )
        return Route(poly, listOf(RouteLeg(2600.0, 260.0, null, ms)), 2600.0, 260.0, null)
    }

    /** From [from] north, a right turn [turnM] on, east along y = from + turnM, then north to [dest]. */
    private fun answer(from: LatLng, turnM: Double): Route {
        val fy = (from.lat - lat) * 111_320.0
        val fx = (from.lng - lng0) * mPerDegLng
        val y = fy + turnM
        val north = (0..turnM.toInt()).map { at(fx, fy + it) }
        val east = (1..((2000.0 - fx) / 10).toInt()).map { at(fx + it * 10.0, y) }
        val up = (1..((600.0 - y) / 10).toInt()).map { at(2000.0, y + it * 10.0) } + dest
        val poly = north + east + up
        val ms = listOf(
            Maneuver(ManeuverType.DEPART, "Head north", poly.first(), turnM, turnM / 10.0),
            Maneuver(ManeuverType.TURN_RIGHT, "Turn right onto Elm Street", at(fx, y), 2000.0 - fx, (2000.0 - fx) / 10.0, road = "Elm Street"),
            Maneuver(ManeuverType.TURN_LEFT, "Turn left onto Oak Avenue", at(2000.0, y), 600.0 - y, (600.0 - y) / 10.0, road = "Oak Avenue"),
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

    @Test fun `a reroute with an unmakeable first turn shows at once and is swapped once the car is past it`() {
        NavStringsRegistry.setLocale(Locale.ENGLISH)
        val asks = AtomicInteger()
        val answers = ArrayList<Route>()
        val ctx = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = NoPrefs
            override fun getSystemService(name: String): Any? = null
            override fun getApplicationContext(): Context = this
        }
        // The first answer turns 5 m ahead of the car (out of reach at 10 m/s); the second, asked
        // from further on, 200 m ahead.
        val data = java.lang.reflect.Proxy.newProxyInstance(MapDataSource::class.java.classLoader, arrayOf(MapDataSource::class.java)) { _, m, args ->
            if (m.name != "directions") throw AssertionError("unexpected fetch: ${m.name}")
            val from = args[0] as LatLng
            val r = answer(from, if (asks.incrementAndGet() == 1) 5.0 else 200.0)
            synchronized(answers) { answers += r }
            listOf(r)
        } as MapDataSource
        val s = NavSession(data, VoiceGuide(ctx).apply { muted = true }, Haptics(ctx), DiagLog(ctx)).apply { liveRechecks = false; replayMode = false }
        val notes = java.util.Collections.synchronizedList(ArrayList<String>())
        s.onNote = { notes += it }
        s.start(planned(), dest, "Davis Depot")
        // The JVM's SystemClock reads 0, so the last adoption is put in the past or the reroute
        // cooldown holds every ask; a second plain reroute stays inside the cooldown after the
        // first lands, so a second ask can only come from the late-turn follow-up.
        NavSession::class.java.getDeclaredField("lastRerouteAdoptMs").apply { isAccessible = true }.setLong(s, -NavSession.REROUTE_COOLDOWN_MS * 10)
        // Along the street, then off it to the north until the reroute is asked.
        var y = 0.0
        for (x in 0..20) s.onLocation(at(x * 10.0, 0.0), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 90.0)
        while (asks.get() == 0 && y < 300.0) { y += 10.0; s.onLocation(at(200.0, y), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 0.0); Thread.sleep(100) }
        assertEquals("the reroute was asked once by ${y.toInt()} m off", 1, asks.get())
        // The first answer is driven at once, its near turn left unsaid.
        val deadline = System.currentTimeMillis() + 3000
        while (s.state.value.route !== synchronized(answers) { answers.firstOrNull() } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val first = synchronized(answers) { answers[0] }
        assertTrue("the first answer is shown before any second ask", s.state.value.route === first && asks.get() == 1)
        assertEquals(1, s.state.value.nav.chainedStep)
        assertTrue(NavEngine.TURN_NOW_SLOT in s.state.value.nav.chainedBands)
        // The car goes on north past that turn; asked again from there, the answer is swapped in.
        val end = System.currentTimeMillis() + 6000
        while (asks.get() < 2 && System.currentTimeMillis() < end) {
            y += 3.0; s.onLocation(at(200.0, y), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 0.0); Thread.sleep(300)
        }
        assertEquals("$notes", 2, asks.get())
        val until = System.currentTimeMillis() + 3000
        while (s.state.value.route !== synchronized(answers) { answers.getOrNull(1) } && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue("the second answer took over", s.state.value.route === synchronized(answers) { answers[1] })
        assertEquals(-1, s.state.value.nav.chainedStep)
        s.stop()
    }

    @Test fun `a car that makes the near turn after all is not asked about again`() {
        NavStringsRegistry.setLocale(Locale.ENGLISH)
        val asks = AtomicInteger()
        val answers = ArrayList<Route>()
        val ctx = object : ContextWrapper(null) {
            override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = NoPrefs
            override fun getSystemService(name: String): Any? = null
            override fun getApplicationContext(): Context = this
        }
        // The first answer turns 5 m ahead of the car (out of reach at 10 m/s); the second, asked
        // from further on, 200 m ahead.
        val data = java.lang.reflect.Proxy.newProxyInstance(MapDataSource::class.java.classLoader, arrayOf(MapDataSource::class.java)) { _, m, args ->
            if (m.name != "directions") throw AssertionError("unexpected fetch: ${m.name}")
            val from = args[0] as LatLng
            val r = answer(from, if (asks.incrementAndGet() == 1) 5.0 else 200.0)
            synchronized(answers) { answers += r }
            listOf(r)
        } as MapDataSource
        val s = NavSession(data, VoiceGuide(ctx).apply { muted = true }, Haptics(ctx), DiagLog(ctx)).apply { liveRechecks = false; replayMode = false }
        val notes = java.util.Collections.synchronizedList(ArrayList<String>())
        s.onNote = { notes += it }
        s.start(planned(), dest, "Davis Depot")
        // The JVM's SystemClock reads 0, so the last adoption is put in the past or the reroute
        // cooldown holds every ask; a second plain reroute stays inside the cooldown after the
        // first lands, so a second ask can only come from the late-turn follow-up.
        NavSession::class.java.getDeclaredField("lastRerouteAdoptMs").apply { isAccessible = true }.setLong(s, -NavSession.REROUTE_COOLDOWN_MS * 10)
        // Along the street, then off it to the north until the reroute is asked.
        var y = 0.0
        for (x in 0..20) s.onLocation(at(x * 10.0, 0.0), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 90.0)
        while (asks.get() == 0 && y < 300.0) { y += 10.0; s.onLocation(at(200.0, y), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 0.0); Thread.sleep(100) }
        assertEquals("the reroute was asked once by ${y.toInt()} m off", 1, asks.get())
        // The first answer is driven at once, its near turn left unsaid.
        val deadline = System.currentTimeMillis() + 3000
        while (s.state.value.route !== synchronized(answers) { answers.firstOrNull() } && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val first = synchronized(answers) { answers[0] }
        assertTrue("the first answer is shown before any second ask", s.state.value.route === first && asks.get() == 1)
        assertEquals(1, s.state.value.nav.chainedStep)
        assertTrue(NavEngine.TURN_NOW_SLOT in s.state.value.nav.chainedBands)
        // The car makes the turn after all: east along the new street.
        val turnY = y + 5.0
        for (x in 1..30) { s.onLocation(at(200.0 + x * 3.0, turnY), speedMps = 10.0, accuracyM = 5.0, bearingDeg = 90.0); Thread.sleep(150) }
        assertEquals("$notes", 1, asks.get())
        assertTrue("$notes", notes.any { "made the first turn" in it })
        s.stop()
    }
}
