package app.vela.core

import app.vela.core.config.Calibration
import app.vela.core.data.google.GoogleResponse
import app.vela.core.data.google.SearchPb
import app.vela.core.data.google.arr
import app.vela.core.data.google.at
import app.vela.core.data.google.parse.SearchParser
import app.vela.core.data.google.str
import app.vela.core.model.LatLng
import kotlinx.serialization.json.JsonElement
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Test
import java.net.URLEncoder

/**
 * Live status-vs-hours dump for one place, for diagnosing an open/closed status line that
 * contradicts the weekly hours table (they come from DIFFERENT response blocks: status
 * prefers [118], weekly hours prefer [203]). Env-gated so it is a no-op in CI:
 *
 *   PROBE_QUERY="safeway" PROBE_LAT=.. PROBE_LNG=.. ./gradlew :core:testDebugUnitTest \
 *     --tests app.vela.core.HoursProbeTest --rerun-tasks
 */
class HoursProbeTest {

    @Test
    fun dumpStatusVsHoursBlocks() {
        val q = System.getenv("PROBE_QUERY") ?: return
        val lat = System.getenv("PROBE_LAT")?.toDoubleOrNull() ?: return
        val lng = System.getenv("PROBE_LNG")?.toDoubleOrNull() ?: return

        val pb = SearchPb.build(q, LatLng(lat, lng))
        val url = Calibration.DEFAULT.searchEndpoint +
            "&q=" + URLEncoder.encode(q, "UTF-8") +
            "&pb=" + URLEncoder.encode(pb, "UTF-8")
        val raw = OkHttpClient().newCall(
            Request.Builder().url(url).header("User-Agent", VelaConfig.USER_AGENT).build(),
        ).execute().use { it.body!!.string() }
        val root = GoogleResponse.parse(raw)

        val sb = StringBuilder()
        val results = root.at(64).arr() ?: run { sb.appendLine("PROBE: no [64] results array"); dump(sb); return }
        results.take(8).forEach { e ->
            val node: JsonElement? = e.at(1)
            val name = node.at(11).str() ?: return@forEach
            sb.appendLine("===== $name =====")
            sb.appendLine("  status118  = ${node.at(118, 0, 3, 1, 4, 0).str()}")
            sb.appendLine("  statusRich = ${node.at(203, 1, 4, 0).str()}")
            sb.appendLine("  openStatus = ${node.at(203, 1, 8, 0).str()}")
            sb.appendLine("  hours203   = ${SearchParser.readHours(node.at(203, 0))}")
            sb.appendLine("  hours118   = ${SearchParser.readHours(node.at(118, 0, 3, 0))}")
            // Full [118] structure: is it a LIST of named departments?
            node.at(118).arr()?.forEachIndexed { i, dep ->
                sb.appendLine("  [118][$i] leaves:")
                (0..8).forEach { k ->
                    val v = dep.at(k)
                    val s = v.str() ?: v.arr()?.let { a -> "arr(${a.size}) first=${a.firstOrNull().toString().take(90)}" }
                    if (s != null) sb.appendLine("      [$k] = ${s.take(140)}")
                }
                sb.appendLine("      hours  = ${SearchParser.readHours(dep.at(3, 0))}")
                sb.appendLine("      status = ${dep.at(3, 1, 4, 0).str()}")
            }
        }
        dump(sb)
    }

    // Gradle swallows test stdout by default; write the dump where the runner can read it.
    private fun dump(sb: StringBuilder) {
        val out = System.getenv("PROBE_OUT") ?: return println(sb)
        java.io.File(out).writeText(sb.toString())
    }
}
