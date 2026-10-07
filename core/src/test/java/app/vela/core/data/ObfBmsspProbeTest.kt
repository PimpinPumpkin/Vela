package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import org.junit.Assert.assertTrue

/**
 * Real-road probe for the fork's BMSSP engine, following the fork's obf probe convention:
 * run it with `-DvelaObf=<dir>` where the dir holds the committed test region
 * (`delaware.obf` + an `index.json` entry); without the flag the whole class skips.
 *
 * Delaware end-to-end (Wilmington -> Rehoboth Beach, ~130 km): the route must come back
 * coherent, and the probe line reports the Dijkstra-vs-BMSSP head-to-head on the SAME
 * welded graph (the numbers behind FORK.md's table).
 */
class ObfBmsspProbeTest {
    private val dir: File? = System.getProperty("velaObf")?.let { File(it) }
        ?.takeIf { File(it, "index.json").exists() && File(it, "delaware.obf").exists() }
    private val anyRegions: File? = System.getProperty("velaObf")?.let { File(it) }
        ?.takeIf { File(it, "index.json").exists() }

    private val wilmington = LatLng(39.7459, -75.5466)
    private val rehoboth = LatLng(38.7209, -75.0760)

    @Test
    fun bmsspRoutesACrossStateDrive() {
        assumeTrue("set -DvelaObf=<dir with delaware.obf + index.json> to run", dir != null)
        val engine = ObfBmsspRouteEngine(dir!!)
        try {
            val routes = engine.route(wilmington, rehoboth, TravelMode.DRIVE)
            assertTrue("no BMSSP route over delaware.obf (${engine.lastStats})", routes.isNotEmpty())
            val r = routes.first()
            // Delaware is ~190 km north-south; Wilmington->Rehoboth is well over 100 km by road.
            assertTrue("implausible distance ${r.distanceMeters}", r.distanceMeters > 100_000.0)
            assertTrue("implausible time ${r.durationSeconds}", r.durationSeconds > 3_000.0)
            assertTrue("degenerate polyline ${r.polyline.size}", r.polyline.size > 20)
            assertTrue("no maneuvers", r.legs.first().maneuvers.size >= 2)
            println("bmssp head-to-head: ${engine.probeTrip(wilmington, rehoboth)}")
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun graphBuildReachesTheRoutingTreeOfEveryInstalledRegion() {
        assumeTrue("set -DvelaObf=<dir with index.json + obf files> to run", anyRegions != null)
        // index.json entries look like {"id":"delaware","bbox":[s,w,n,e]}
        val groups = Regex(
            "\"id\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"bbox\"\\s*:\\s*\\[\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)\\s*,\\s*([-0-9.]+)",
        ).findAll(File(anyRegions!!, "index.json").readText()).map { it.groupValues }.toList()
        assumeTrue("index.json lists no regions", groups.isNotEmpty())
        val engine = ObfBmsspRouteEngine(anyRegions!!)
        try {
            for (g in groups) {
                val s = g[2].toDouble(); val w = g[3].toDouble()
                val n = g[4].toDouble(); val e = g[5].toDouble()
                val midLat = (s + n) / 2
                val midLng = (w + e) / 2
                // A short interior trip must still reach the routing tree. Snap or search
                // outcomes do not matter here; failing to build any graph from a box the
                // region provably covers means the subregion walk is broken for the shape
                // of that file (country-sized files with split trees fail this when the
                // search request box is assembled wrong; single-tile files hide it).
                engine.route(
                    LatLng(midLat, midLng),
                    LatLng(midLat + (n - s) / 20, midLng + (e - w) / 20),
                    TravelMode.DRIVE,
                )
                val stats = engine.lastStats
                assertFalse(
                    "region '${g[1]}' built no graph from a trip box inside its own bbox ($stats)",
                    stats.contains("graph build aborted") || stats.contains("no covering regions") ||
                        stats.contains("no readable obf files"),
                )
            }
        } finally {
            engine.shutdown()
        }
    }

    @Test
    fun bmsspRefusesAnImpossibleBudgetInstantly() {
        assumeTrue("set -DvelaObf=<dir with delaware.obf + index.json> to run", dir != null)
        val engine = ObfBmsspRouteEngine(dir!!)
        try {
            val t0 = System.currentTimeMillis()
            val routes = engine.route(wilmington, rehoboth, TravelMode.DRIVE, maxMs = 1)
            assertTrue("a 1 ms budget must be refused, not half-routed", routes.isEmpty())
            assertTrue("refusal took ${System.currentTimeMillis() - t0} ms", System.currentTimeMillis() - t0 < 2_000)
        } finally {
            engine.shutdown()
        }
    }
}
