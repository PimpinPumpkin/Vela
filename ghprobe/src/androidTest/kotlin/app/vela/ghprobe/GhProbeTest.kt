package app.vela.ghprobe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
import com.graphhopper.config.CHProfile
import com.graphhopper.config.Profile
import com.graphhopper.matching.MapMatching
import com.graphhopper.matching.Observation
import com.graphhopper.routing.WeightingFactory
import com.graphhopper.routing.weighting.SpeedWeighting
import com.graphhopper.util.EdgeIteratorState
import com.graphhopper.util.GHUtility
import com.graphhopper.util.PMap
import com.graphhopper.util.shapes.GHPoint
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.zip.ZipInputStream

/**
 * On-device proof: does GraphHopper v11 LOAD a prebuilt graph + ROUTE + MAP-MATCH on real ART?
 * The known risk is Janino (the custom-model compiler) — it runs when the weighting is built
 * during importOrLoad()/route(). If GraphHopper is Android-viable this passes; if Janino can't
 * compile on ART, this fails HERE with a clear stacktrace (tag GHPROBE in logcat).
 *
 * The graph (Monaco, 5 MB) is built OFF-device and bundled zipped in androidTest assets — the
 * realistic shipping model. We never import .pbf on-device.
 */
@RunWith(AndroidJUnit4::class)
class GhProbeTest {
    private val tag = "GHPROBE"

    @Test
    fun graphHopperLoadsRoutesAndMatchesOnDevice() {
        val ctx = InstrumentationRegistry.getInstrumentation().context // test APK — holds androidTest assets
        val dir = File(ctx.cacheDir, "monaco-graph")
        unzipAsset("monaco-graph.zip", dir)
        Log.i(tag, "graph files: ${dir.list()?.sorted()}")

        val hopper = loadGraph(dir)
        val rsp = hopper.route(GHRequest(43.7325, 7.4189, 43.7400, 7.4290).setProfile("car"))
        assertTrue("route errors: ${rsp.errors}", !rsp.hasErrors())
        val path = rsp.best
        Log.i(tag, "ROUTED ${Math.round(path.distance)} m, ${path.instructions.size} instructions")

        val pts = path.points
        val obs = ArrayList<Observation>()
        var i = 0
        while (i < pts.size()) { obs.add(Observation(GHPoint(pts.getLat(i), pts.getLon(i)))); i += 4 }
        val tMatch = System.currentTimeMillis()
        val mr = MapMatching.fromGraphHopper(hopper, PMap().putObject("profile", "car")).match(obs)
        val names = LinkedHashSet<String>()
        for (em in mr.edgeMatches) em.edgeState.name.takeIf { it.isNotEmpty() }?.let { names.add(it) }
        Log.i(tag, "MATCHED ${mr.edgeMatches.size} edges in ${System.currentTimeMillis() - tMatch}ms, names=$names")

        // close() tries to unmap the MMAP buffer via Unsafe.invokeCleaner, absent on Android — harmless
        // here (the real app keeps one engine for the process lifetime and never per-route closes).
        runCatching { hopper.close() }.onFailure { Log.w(tag, "close() unmap quirk (Android, harmless): ${it.message}") }
        assertTrue("no street names recovered on-device", names.isNotEmpty())
    }

    /**
     * Closes the one open perf question: a real METRO graph, loaded from INTERNAL storage (the
     * production target — `cacheDir`/`filesDir`, fast MMAP), routes a 24-mi trip quickly on-device.
     * (The in-app test was slow only because adb can push solely to *external* FUSE storage, whose
     * MMAP is slow for routing's random access. Internal storage has no such cost.)
     */
    @Test
    fun metroGraphRoutesFastFromInternalStorage() {
        val ctx = InstrumentationRegistry.getInstrumentation().context
        // The 21 MB CH metro graph is NOT committed (git-ignored) — regenerate it with the graph
        // builder and drop seam-graph.zip in androidTest/assets to run this perf check. Skip otherwise.
        org.junit.Assume.assumeTrue(
            "seam-graph.zip not bundled — see the graph builder",
            ctx.assets.list("")?.contains("seam-graph.zip") == true,
        )
        val dir = File(ctx.cacheDir, "seam-graph") // INTERNAL storage
        val tUnzip = System.currentTimeMillis()
        unzipAsset("seam-graph.zip", dir)
        Log.i(tag, "metro graph: ${dir.list()?.size} files unzipped in ${System.currentTimeMillis() - tUnzip}ms")

        val hopper = loadGraph(dir, useCH = true) // CH = fast routing (no-CH flexible was 7.6 s on-device)
        // the real test trip: Silver Firs (Everett) -> Raising Cane's (Seattle)
        val tRoute = System.currentTimeMillis()
        val rsp = hopper.route(GHRequest(47.86, -122.20, 47.66, -122.30).setProfile("car"))
        val routeMs = System.currentTimeMillis() - tRoute
        assertTrue("route errors: ${rsp.errors}", !rsp.hasErrors())
        val path = rsp.best
        Log.i(tag, "METRO ROUTE (internal storage, CH): ${Math.round(path.distance / 1609.0)} mi, " +
            "${path.instructions.size} steps in ${routeMs}ms")
        runCatching { hopper.close() }
        assertTrue("metro route should be fast on-device with CH (<1s); was ${routeMs}ms", routeMs < 1000)
    }

    /** Load a prebuilt graph with the three Android workarounds (MMAP / SpeedWeighting / no Janino).
     *  Same recipe `GraphHopperRouteEngine` ships. */
    private fun loadGraph(dir: File, useCH: Boolean = false): GraphHopper {
        // MMAP, not the default RAM_STORE: RAMDataAccess's static VarHandle init calls
        // withInvokeExactBehavior() which ART lacks; MMapDataAccess doesn't. (RAM_STORE & MMAP share
        // the on-disk format, so a desktop-built graph loads as MMAP with no rebuild.)
        val cfg = GraphHopperConfig()
        cfg.putObject("graph.location", dir.absolutePath)
        cfg.putObject("graph.dataaccess", "MMAP")
        cfg.putObject("graph.encoded_values", "car_access, car_average_speed, road_access")
        cfg.putObject("import.osm.ignored_highways", "") // required by init() validation (import-only)
        cfg.setProfiles(listOf(Profile("car").setCustomModel(GHUtility.loadCustomModelFromJar("car.json"))))
        if (useCH) cfg.setCHProfiles(listOf(CHProfile("car"))) // use the prebuilt Contraction Hierarchies
        // Janino dodge: v11 compiles the custom-model weighting via Janino (-> JVM bytecode ART can't
        // load). Override the factory to a Janino-free SpeedWeighting + access block (mirrors car.json).
        val hopper = object : GraphHopper() {
            override fun createWeightingFactory(): WeightingFactory =
                WeightingFactory { _, _, _ ->
                    val speed = encodingManager.getDecimalEncodedValue("car_average_speed")
                    val access = encodingManager.getBooleanEncodedValue("car_access")
                    object : SpeedWeighting(speed) {
                        override fun calcEdgeWeight(edgeState: EdgeIteratorState, reverse: Boolean): Double {
                            val ok = if (reverse) edgeState.getReverse(access) else edgeState.get(access)
                            return if (!ok) Double.POSITIVE_INFINITY else super.calcEdgeWeight(edgeState, reverse)
                        }
                    }
                }
        }
        hopper.init(cfg)
        val tLoad = System.currentTimeMillis()
        hopper.importOrLoad()
        Log.i(tag, "LOADED ${dir.name} in ${System.currentTimeMillis() - tLoad}ms")
        return hopper
    }

    private fun unzipAsset(asset: String, outDir: File) {
        outDir.deleteRecursively(); outDir.mkdirs()
        val ctx = InstrumentationRegistry.getInstrumentation().context
        ZipInputStream(ctx.assets.open(asset)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val f = File(outDir, e.name)
                if (e.isDirectory) f.mkdirs() else { f.parentFile?.mkdirs(); f.outputStream().use { zis.copyTo(it) } }
                e = zis.nextEntry
            }
        }
    }
}
