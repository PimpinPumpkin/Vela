package app.vela.core.data

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * On-demand harness for the obf speed-limit lookup, against a REAL region file:
 *
 *   ./gradlew :core:testDebugUnitTest --tests '*ObfSpeedLimitProbeTest' -DvelaObf=<dir> --rerun-tasks
 *
 * where <dir> holds `delaware.obf` (the `obf-regions` release asset) and an `index.json` like
 * ObfStore writes (`[{"id":"delaware","bbox":[38.44,-75.80,39.85,-74.98]}]`). Skipped when the
 * property is absent, so CI never needs the 7 MB file. The points are the Delaware fixture: a
 * motorway with a posted limit, a highway with none, and open water with no road at all.
 */
class ObfSpeedLimitProbeTest {
    private val dir: File? = System.getProperty("velaObf")?.let { File(it) }?.takeIf { File(it, "index.json").exists() }

    @Test
    fun aPostedMotorwayLimitReadsBackInKmh() {
        assumeTrue("set -DvelaObf=<dir with delaware.obf + index.json>", dir != null)
        val engine = ObfRouteEngine(dir!!)
        // The Puncheon Run Connector at Dover, a motorway posted at 55 mph (OSM maxspeed, stored by
        // OsmAnd as 24.44 m/s).
        println("probe: " + engine.probeRoadLimit(39.150, -75.494))
        val kmh = engine.currentRoadLimit(39.150, -75.494)
        println("obf speed limit on the Puncheon Run Connector: $kmh km/h")
        assertTrue("expected about 88 km/h (55 mph), got $kmh", kmh != null && kmh > 84.0 && kmh < 92.0)
        // Second call reuses the lookup context (a cache hit on the same tiles).
        val again = engine.currentRoadLimit(39.1502, -75.4942)
        assertTrue("second lookup $again", again != null)
        engine.shutdown()
    }

    @Test
    fun anUntaggedRoadAndOpenWaterReadNull() {
        assumeTrue("set -DvelaObf=<dir with delaware.obf + index.json>", dir != null)
        val engine = ObfRouteEngine(dir!!)
        // South State Street in Dover carries no maxspeed tag in OSM: the badge must blank, never guess.
        assertNull(engine.currentRoadLimit(39.1197, -75.5160))
        // Delaware Bay, inside the region box, kilometres from any road.
        assertNull(engine.currentRoadLimit(39.05, -75.25))
        engine.shutdown()
    }
}
