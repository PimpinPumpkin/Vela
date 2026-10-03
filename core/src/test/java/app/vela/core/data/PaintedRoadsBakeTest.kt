package app.vela.core.data

import app.vela.core.model.LatLng
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The painted-roads BAKE, run on demand (skipped otherwise):
 * `./gradlew :core:testDebugUnitTest --tests '*PaintedRoadsBakeTest' -DvelaPaintIn=<roads.geojsonseq>
 * -DvelaPaintOut=<marks.geojsonseq> --rerun-tasks`. The input is `osmium export` of a region filtered
 * to streets, crossings, stop signs and signals (scripts/bake-painted-roads.sh); the output feeds
 * tippecanoe. Properties: k (kind), off (meters), w (meters), icon, rot (degrees).
 */
class PaintedRoadsBakeTest {
    @Test fun bake() {
        val inPath = System.getProperty("velaPaintIn"); val outPath = System.getProperty("velaPaintOut")
        assumeTrue(inPath != null && outPath != null)
        val ways = ArrayList<PaintedRoads.Way>(); val nodes = ArrayList<PaintedRoads.Node>()
        val json = Json { ignoreUnknownKeys = true }
        File(inPath!!).bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trimStart('\u001e').trim()
                if (line.isEmpty()) continue
                val f = json.parseToJsonElement(line).jsonObject
                val g = f["geometry"]?.jsonObject ?: continue
                val tags = (f["properties"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
                when (g["type"]?.jsonPrimitive?.content) {
                    "LineString" -> {
                        val pts = (g["coordinates"] as JsonArray).map { c -> val a = c.jsonArray; LatLng(a[1].jsonPrimitive.double, a[0].jsonPrimitive.double) }
                        if (pts.size >= 2) ways += PaintedRoads.Way(tags, pts)
                    }
                    "Point" -> if (tags["highway"] in setOf("stop", "traffic_signals", "crossing")) {
                        val a = g["coordinates"]!!.jsonArray
                        nodes += PaintedRoads.Node(tags, LatLng(a[1].jsonPrimitive.double, a[0].jsonPrimitive.double))
                    }
                }
            }
        }
        val t0 = System.currentTimeMillis()
        val marks = PaintedRoads.build(ways, nodes)
        println("PAINTBAKE ways=${ways.size} nodes=${nodes.size} marks=${marks.size} in ${System.currentTimeMillis() - t0} ms; " +
            marks.groupingBy { it.kind }.eachCount())
        File(outPath!!).bufferedWriter().use { w ->
            for (m in marks) {
                val geom = if (m.kind == PaintedRoads.Kind.ARROW) {
                    "{\"type\":\"Point\",\"coordinates\":[%.7f,%.7f]}".format(m.points[0].lng, m.points[0].lat)
                } else m.points.joinToString(",", "{\"type\":\"LineString\",\"coordinates\":[", "]}") { "[%.7f,%.7f]".format(it.lng, it.lat) }
                val props = StringBuilder("\"k\":\"${m.kind.name}\"")
                if (m.offsetM != 0.0) props.append(",\"off\":%.2f".format(m.offsetM))
                if (m.widthM != 0.0) props.append(",\"w\":%.2f".format(m.widthM))
                if (m.icon.isNotEmpty()) props.append(",\"icon\":\"${m.icon}\",\"rot\":%.1f".format(m.rotDeg))
                w.write("{\"type\":\"Feature\",\"properties\":{$props},\"geometry\":$geom}\n")
            }
        }
    }
}
