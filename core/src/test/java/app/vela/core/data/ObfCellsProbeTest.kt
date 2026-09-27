package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.TravelMode
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * On-demand harness: routes across grid-cell obf files (scripts/build-cells-region.sh) and over
 * the whole-region obf, and compares them.
 *
 *   ./gradlew :core:testDebugUnitTest --tests '*ObfCellsProbeTest' -DvelaCells=<dir> --rerun-tasks
 *
 * <dir> holds `cells/` (the Delaware cells' .obf files + an index.json of their ids and boxes) and
 * `whole/` (delaware.obf + index.json). Skipped without the property. Each trip crosses at least
 * one cell edge (39.0 N, 39.5 N or 75.5 W).
 */
class ObfCellsProbeTest {
    private val root: File? = System.getProperty("velaCells")?.let { File(it) }
        ?.takeIf { File(it, "cells/index.json").exists() && File(it, "whole/index.json").exists() }

    private val trips = listOf(
        "Middletown to Newark (39.5 N)" to (LatLng(39.4496, -75.7163) to LatLng(39.6837, -75.7497)),
        "Dover to the air base (75.5 W)" to (LatLng(39.1582, -75.5244) to LatLng(39.1296, -75.4660)),
        "Milford to Dover (39.0 N, 75.5 W)" to (LatLng(38.9126, -75.4277) to LatLng(39.1582, -75.5244)),
        "Smyrna to Wilmington (39.5 N)" to (LatLng(39.2998, -75.6047) to LatLng(39.7447, -75.5484)),
    )

    @Test
    fun cellsRouteLikeTheWholeRegion() {
        assumeTrue("set -DvelaCells=<dir with cells/ and whole/>", root != null)
        val cells = ObfRouteEngine(File(root!!, "cells"))
        val whole = ObfRouteEngine(File(root, "whole"))
        for ((name, trip) in trips) {
            val c = timed { cells.route(trip.first, trip.second, TravelMode.DRIVE) }
            val w = timed { whole.route(trip.first, trip.second, TravelMode.DRIVE) }
            val rc = c.first.firstOrNull()
            val rw = w.first.firstOrNull()
            println("cells: $name | cells ${fmt(rc)} in ${c.second} ms | whole ${fmt(rw)} in ${w.second} ms")
            assertTrue("$name: no route over the cells", rc != null)
            assertTrue("$name: no route over the whole region", rw != null)
            val dd = abs(rc!!.distanceMeters - rw!!.distanceMeters) / rw.distanceMeters
            assertTrue("$name: distance differs by ${"%.1f".format(dd * 100)}%", dd < 0.02)
        }
        cells.shutdown()
        whole.shutdown()
    }

    private fun fmt(r: Route?) = if (r == null) "none"
    else "%.2f km, %.1f min, %d steps".format(r.distanceMeters / 1000, r.durationSeconds / 60, r.maneuvers.size)

    private fun <T> timed(block: () -> T): Pair<T, Long> {
        val t0 = System.currentTimeMillis()
        return block() to (System.currentTimeMillis() - t0)
    }
}
