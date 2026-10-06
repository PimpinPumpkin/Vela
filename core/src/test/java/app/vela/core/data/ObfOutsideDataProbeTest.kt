package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * On-demand check, against a REAL file, that a trip inside a region's BOX but outside its data
 * is refused at once:
 *
 *   ./gradlew :core:testDebugUnitTest --tests '*ObfOutsideDataProbeTest' -DvelaObf=<dir> --rerun-tasks
 *
 * where <dir> holds `california-norcal.obf` and an `index.json` like ObfStore writes. Reno is in
 * Nevada and inside Northern California's bounding box, which reaches east across that state.
 * The router used to snap both ends to the nearest California roads, tens of kilometers away,
 * and answer a 6 km trip with a 235 km route. A trip in Sacramento must still route. Skipped
 * when the property is absent.
 */
class ObfOutsideDataProbeTest {
    private val dir: File? = System.getProperty("velaObf")?.let { File(it) }
        ?.takeIf { File(it, "index.json").exists() && File(it, "california-norcal.obf").exists() }

    @Test
    fun aTripOutsideTheDataIsRefusedQuickly() {
        assumeTrue("set -DvelaObf=<dir with california-norcal.obf and index.json>", dir != null)
        val engine = ObfRouteEngine(dir!!)
        val t0 = System.currentTimeMillis()
        val outside = engine.route(LatLng(39.5296, -119.8138), LatLng(39.5450, -119.7600), TravelMode.DRIVE, false, false, false, null)
        val outsideMs = System.currentTimeMillis() - t0
        val inside = engine.route(LatLng(38.5816, -121.4944), LatLng(38.5449, -121.7405), TravelMode.DRIVE, false, false, false, null)
        println("outside probe: outside the data ${outside.size} route(s) in $outsideMs ms; inside ${inside.firstOrNull()?.let { "%.1f km".format(it.distanceMeters / 1000) }}")
        engine.shutdown()
        assertTrue("a route came back for a trip the file has no roads for", outside.isEmpty())
        assertTrue("refusing took $outsideMs ms", outsideMs < 3_000)
        assertTrue("the trip inside the data found no route", inside.isNotEmpty())
    }
}
