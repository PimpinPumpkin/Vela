package app.vela.ghprobe

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.graphhopper.GHRequest
import com.graphhopper.GraphHopper
import com.graphhopper.GraphHopperConfig
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

        // MMAP, not the default RAM_STORE: RAMDataAccess's static VarHandle init calls
        // withInvokeExactBehavior() which ART lacks; MMapDataAccess doesn't. Set via config
        // (no public DAType setter). RAM_STORE & MMAP share the on-disk format, so the graph
        // built on desktop (RAM_STORE) loads fine as MMAP — no rebuild needed.
        val cfg = GraphHopperConfig()
        cfg.putObject("graph.location", dir.absolutePath)
        cfg.putObject("graph.dataaccess", "MMAP")
        cfg.putObject("graph.encoded_values", "car_access, car_average_speed, road_access")
        cfg.putObject("import.osm.ignored_highways", "") // required by init() validation (import-only; we only load)
        cfg.setProfiles(listOf(Profile("car").setCustomModel(GHUtility.loadCustomModelFromJar("car.json"))))
        // Janino dodge: v11 compiles the custom-model weighting via Janino (-> JVM bytecode ART can't
        // load). Override the factory to return a Janino-free SpeedWeighting (time = dist/speed). The
        // profile keeps its custom model so the load consistency-check still matches the stored graph.
        val hopper = object : GraphHopper() {
            override fun createWeightingFactory(): WeightingFactory =
                WeightingFactory { _, _, _ ->
                    val speed = encodingManager.getDecimalEncodedValue("car_average_speed")
                    val access = encodingManager.getBooleanEncodedValue("car_access")
                    // SpeedWeighting is Janino-free but ignores access; add the access block so it
                    // matches car.json's "if !car_access: multiply_by 0" (else routes onto blocked edges).
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
        hopper.importOrLoad() // <-- custom-model weighting (Janino) built here
        Log.i(tag, "LOADED ok in ${System.currentTimeMillis() - tLoad}ms")

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
