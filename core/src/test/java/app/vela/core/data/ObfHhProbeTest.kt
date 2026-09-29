package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * On-demand harness for long offline routes over the highway hierarchy (HH), against REAL files:
 *
 *   ./gradlew :core:testDebugUnitTest --tests '*ObfHhProbeTest' -DvelaObf=<dir> --rerun-tasks
 *
 * where <dir> holds region obf files (`<id>.obf`, baked with HH), an `index.json` like ObfStore
 * writes (`[{"id":"...","bbox":[S,W,N,E]}]`) and `hh-trips.txt`, one trip per line as
 * `lat lng lat lng`. Every trip must route by car in under [LIMIT_MS]; without HH a 150 km trip
 * over a dense region runs out of the engine's memory budget instead (SPEC 4.5). Skipped when the
 * property or the trip list is absent, so CI never needs the files.
 */
class ObfHhProbeTest {
    private val dir: File? = System.getProperty("velaObf")?.let { File(it) }
        ?.takeIf { File(it, "index.json").exists() && File(it, "hh-trips.txt").exists() }

    @Test
    fun longDrivesRouteQuickly() {
        assumeTrue("set -DvelaObf=<dir with obf files, index.json and hh-trips.txt>", dir != null)
        val engine = ObfRouteEngine(dir!!)
        val trips = File(dir, "hh-trips.txt").readLines().filter { it.isNotBlank() }.map { l -> l.trim().split(Regex("\\s+")).map { it.toDouble() } }
        for (t in trips) {
            val start = System.currentTimeMillis()
            val route = engine.route(LatLng(t[0], t[1]), LatLng(t[2], t[3]), TravelMode.DRIVE, false, false, false, null).firstOrNull()
            val ms = System.currentTimeMillis() - start
            println("hh probe ${t.joinToString(",")}: ${ms} ms, ${route?.let { "%.1f km, %d maneuvers".format(it.distanceMeters / 1000, it.maneuvers.size) } ?: "no route"}")
            assertTrue("no route for $t", route != null)
            assertTrue("$t took $ms ms", ms < LIMIT_MS)
        }
        engine.shutdown()
    }

    private companion object {
        const val LIMIT_MS = 5_000L
    }
}
