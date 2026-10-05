package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * On-demand check that a route search stops when its caller's time is up, against a REAL file:
 *
 *   ./gradlew :core:testDebugUnitTest --tests '*ObfStopProbeTest' -DvelaObf=<dir> --rerun-tasks
 *
 * where <dir> holds `delaware.obf` and an `index.json` like ObfStore writes. A walk the length of
 * the state has no shortcut set and takes seconds; with a 300 ms limit it must come back empty in
 * about that long, and the same search with no limit must still find the route. Skipped when the
 * property is absent, so CI never needs the file.
 */
class ObfStopProbeTest {
    private val dir: File? = System.getProperty("velaObf")?.let { File(it) }
        ?.takeIf { File(it, "index.json").exists() && File(it, "delaware.obf").exists() }

    @Test
    fun aSearchStopsWhenItsTimeIsUp() {
        assumeTrue("set -DvelaObf=<dir with delaware.obf and index.json>", dir != null)
        val engine = ObfRouteEngine(dir!!)
        val north = LatLng(39.7459, -75.5466) // Wilmington
        val south = LatLng(38.7209, -75.0760) // Rehoboth Beach
        val t0 = System.currentTimeMillis()
        val whole = engine.route(north, south, TravelMode.WALK, false, false, false, null, null)
        val wholeMs = System.currentTimeMillis() - t0
        val t1 = System.currentTimeMillis()
        val cut = engine.route(north, south, TravelMode.WALK, false, false, false, null, 300L)
        val cutMs = System.currentTimeMillis() - t1
        println("stop probe: no limit ${whole.size} route(s) in $wholeMs ms, 300 ms limit ${cut.size} route(s) in $cutMs ms")
        engine.shutdown()
        assertTrue("the unlimited search found no route", whole.isNotEmpty())
        assumeTrue("this machine finished the walk inside the limit, nothing to stop", wholeMs > 600)
        assertTrue("a stopped search returned a route", cut.isEmpty())
        assertTrue("the search ran $cutMs ms past a 300 ms limit", cutMs < 1_000)
    }
}
