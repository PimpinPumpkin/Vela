package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * On-demand harness for the Latin road-name aliases an obf route carries (issue #184 offline):
 *
 *   ./gradlew :core:testDebugUnitTest --tests '*ObfRoadNamesProbeTest' -DvelaObf=<dir> --rerun-tasks
 *
 * with `israel-and-palestine.obf` (the `obf-regions` asset) and an `index.json` entry
 * `{"id":"israel-and-palestine","bbox":[29.4,34.2,33.4,35.95]}` in <dir>. Skipped without the
 * property. A Tel Aviv drive must come back with Hebrew road names paired to Latin ones.
 */
class ObfRoadNamesProbeTest {
    private val dir: File? = System.getProperty("velaObf")?.let { File(it) }
        ?.takeIf { File(it, "israel-and-palestine.obf").exists() && File(it, "index.json").exists() }

    @Test
    fun aTelAvivDriveCarriesHebrewToLatinAliases() {
        assumeTrue("set -DvelaObf=<dir with israel-and-palestine.obf + index.json>", dir != null)
        val engine = ObfRouteEngine(dir!!)
        val routes = engine.route(LatLng(32.0853, 34.7818), LatLng(32.0640, 34.7700), TravelMode.DRIVE)
        assertTrue("no offline route", routes.isNotEmpty())
        val names = routes.first().roadNamesLatin
        println("obf road names: ${names.size} pairs, e.g. " + names.entries.take(5).joinToString { "${it.key} -> ${it.value}" })
        assertTrue("expected Latin aliases for Hebrew roads, got $names", names.isNotEmpty())
        val hebrew = names.keys.count { k -> k.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HEBREW } }
        assertTrue("keys should be the local (Hebrew) names: $names", hebrew > 0)
        assertTrue("values must be Latin-only", names.values.all { v -> v.none { Character.isLetter(it) && Character.UnicodeScript.of(it.code) != Character.UnicodeScript.LATIN } })
        engine.shutdown()
    }

    @Test
    fun latinAliasRule() {
        assertEquals("Herzl Street", ObfRouteEngine.latinAlias("רחוב הרצל", "Herzl Street"))
        assertNull(ObfRouteEngine.latinAlias("רחוב הרצל", "רחוב הרצל"))
        assertNull(ObfRouteEngine.latinAlias("רחוב הרצל", "רחוב Herzl"))
        assertNull(ObfRouteEngine.latinAlias("Main Street", ""))
        assertNull(ObfRouteEngine.latinAlias("Main Street", null))
    }
}
