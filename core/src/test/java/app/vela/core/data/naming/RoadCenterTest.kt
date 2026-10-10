package app.vela.core.data.naming

import app.vela.core.data.naming.RoadNameTiles.RoadLine
import app.vela.core.model.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot

/** The drawn line moved onto the middle of the map's roads, and left alone past the threshold. */
class RoadCenterTest {
    private val lat = 38.5449
    private val lng0 = -121.7405
    private val kx = 111_320.0 * cos(Math.toRadians(lat))
    private fun pt(e: Double, n: Double) = LatLng(lat + n / 111_320.0, lng0 + e / kx)
    private fun e(p: LatLng) = (p.lng - lng0) * kx
    private fun n(p: LatLng) = (p.lat - lat) * 111_320.0

    private fun road(vararg p: LatLng) = RoadLine("minor", null, p.toList())
    private fun maxStep(l: List<LatLng>) = l.zipWithNext { a, b -> hypot(e(b) - e(a), n(b) - n(a)) }.max()

    @Test fun `a line a lane off the road's middle is drawn on the middle`() {
        val street = road(pt(-50.0, 0.0), pt(450.0, 0.0))
        val line = listOf(pt(0.0, -3.0), pt(400.0, -3.0)) // eastbound, in the right-hand lane
        val out = RoadCenter.nudge(line, listOf(street))
        assertTrue(out.size > 50)
        for (p in out) assertEquals(0.0, n(p), 0.3)
        assertEquals("it is still the same line end to end", 0.0, e(out.first()), 0.5)
        assertEquals(400.0, e(out.last()), 0.5)
        assertTrue(RoadCenter.nudgeCounted(line, listOf(street)).second > 380)
    }

    @Test fun `a long straight diagonal road still pulls the line onto it`() {
        // One 1.7 km segment at 45 degrees: its bounding box covers hundreds of grid cells.
        val road = road(pt(-100.0, -100.0), pt(1100.0, 1100.0))
        val d = 3.0 / Math.sqrt(2.0) // 3 m to the right of the road
        val line = listOf(pt(0.0 + d, 0.0 - d), pt(1000.0 + d, 1000.0 - d))
        val out = RoadCenter.nudge(line, listOf(road))
        assertTrue(out.size > 100)
        for (p in out) assertEquals("off the road's middle", 0.0, Math.abs(e(p) - n(p)) / Math.sqrt(2.0), 0.3)
    }

    @Test fun `past the threshold the line stays where Google put it`() {
        val street = road(pt(-50.0, 0.0), pt(450.0, 0.0))
        val line = listOf(pt(0.0, -(RoadCenter.MAX_OFF_M + 3.0)), pt(400.0, -(RoadCenter.MAX_OFF_M + 3.0)))
        assertSame(line, RoadCenter.nudge(line, listOf(street)))
        assertEquals(0, RoadCenter.nudgeCounted(line, listOf(street)).second)
    }

    @Test fun `a road that crosses the line does not pull it`() {
        val cross = road(pt(200.0, -80.0), pt(200.0, 80.0))
        val line = listOf(pt(0.0, -3.0), pt(400.0, -3.0))
        assertSame(line, RoadCenter.nudge(line, listOf(cross)))
    }

    @Test fun `between two roads about as near, neither is chosen`() {
        val north = road(pt(-50.0, 5.0), pt(450.0, 5.0))
        val south = road(pt(-50.0, -6.0), pt(450.0, -6.0))
        val line = listOf(pt(0.0, 0.0), pt(400.0, 0.0))
        assertSame(line, RoadCenter.nudge(line, listOf(north, south)))
    }

    @Test fun `the nearer carriageway of a divided road is the one taken`() {
        val eastbound = road(pt(-50.0, -8.0), pt(450.0, -8.0))
        val westbound = road(pt(-50.0, 8.0), pt(450.0, 8.0))
        val line = listOf(pt(0.0, -10.5), pt(400.0, -10.5))
        for (p in RoadCenter.nudge(line, listOf(eastbound, westbound))) assertEquals(-8.0, n(p), 0.3)
    }

    @Test fun `where the map has no road the line eases back to Google's and does not jump`() {
        // The map knows the street for the middle 200 m only.
        val street = road(pt(100.0, 0.0), pt(300.0, 0.0))
        val line = listOf(pt(0.0, -4.0), pt(400.0, -4.0))
        val out = RoadCenter.nudge(line, listOf(street))
        val north = out.map { n(it) }
        assertEquals("on the road in the middle", 0.0, north[north.size / 2], 0.3)
        assertEquals("on Google's line at the ends", -4.0, north.first(), 0.3)
        assertEquals(-4.0, north.last(), 0.3)
        assertTrue("no sideways jump from one point to the next", north.zipWithNext { a, b -> kotlin.math.abs(b - a) }.max() < 1.0)
    }

    @Test fun `a turn from one street onto another stays one clean corner`() {
        val a = road(pt(-50.0, 0.0), pt(450.0, 0.0))     // east-west
        val b = road(pt(300.0, -400.0), pt(300.0, 50.0)) // north-south, meets it at 300 E
        // East in the right-hand lane, then right (south) into the right-hand lane.
        val line = listOf(pt(0.0, -3.0), pt(297.0, -3.0), pt(297.0, -300.0))
        val out = RoadCenter.nudge(line, listOf(a, b))
        assertEquals("on the first street well before the corner", 0.0, n(out.first { e(it) > 100.0 }), 0.3)
        assertEquals("on the second well after it", 300.0, e(out.last()), 0.3)
        assertTrue("every point within a lane and a half of where it was", out.all { p ->
            val d = if (n(p) > -30.0 && e(p) < 290.0) kotlin.math.abs(n(p) + 3.0) else if (n(p) < -30.0) kotlin.math.abs(e(p) - 297.0) else 0.0
            d <= 4.5
        })
        assertTrue("no jump at the corner", maxStep(out) < RoadCenter.STEP_M + 3.0)
    }

    @Test fun `on a real tile a line down 3rd Street in Davis is drawn on the street`() {
        val bytes = javaClass.getResourceAsStream("/tiles/davis-14-2651-6288.pbf")!!.use { it.readBytes() }
        val third = RoadNameTiles.decode(bytes, 14, 2651, 6288).filter { it.name == "3rd Street" }.maxBy { it.points.size }.points
        val roads = RoadNameTiles.decodeRoads(bytes, 14, 2651, 6288)
        // The street's own line, moved 3 m to one side: Google's lane.
        val k = 111_320.0 * cos(Math.toRadians(third.first().lat))
        fun off(p: LatLng, q: LatLng, m: Double): LatLng {
            val dx = (q.lng - p.lng) * k; val dy = (q.lat - p.lat) * 111_320.0; val l = hypot(dx, dy).coerceAtLeast(0.01)
            return LatLng(p.lat + (-dx / l) * m / 111_320.0, p.lng + (dy / l) * m / k)
        }
        val lane = third.indices.map { i -> off(third[i], third[(i + 1).coerceAtMost(third.lastIndex)].takeIf { it != third[i] } ?: third[i - 1].let { LatLng(2 * third[i].lat - it.lat, 2 * third[i].lng - it.lng) }, 3.0) }
        val out = RoadCenter.nudge(lane, roads)
        fun toStreet(p: LatLng) = third.zipWithNext { a, b ->
            val ax = (a.lng - p.lng) * k; val ay = (a.lat - p.lat) * 111_320.0; val bx = (b.lng - p.lng) * k; val by = (b.lat - p.lat) * 111_320.0
            val dx = bx - ax; val dy = by - ay; val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else (-(ax * dx + ay * dy) / l2).coerceIn(0.0, 1.0)
            hypot(ax + t * dx, ay + t * dy)
        }.min()
        val before = lane.map { toStreet(it) }.average()
        val after = out.map { toStreet(it) }.average()
        assertTrue("the lane sat $before m off the street", before > 2.5)
        assertTrue("the drawn line sits $after m off it", after < 0.8)
    }

    /**
     * A desk look at one real line: -DvelaNudge=<file holding an encoded polyline> (a line captured
     * with log.tag.VelaCapture). Reads the live tiles under it, nudges it, and writes
     * <file>.json with the line, the result and the roads near it, for drawing.
     */
    @Test fun deskLine() = kotlinx.coroutines.runBlocking {
        val path = System.getProperty("velaNudge")
        org.junit.Assume.assumeTrue("set -DvelaNudge=<file>", !path.isNullOrBlank())
        val line = app.vela.core.data.google.PolylineCodec.decode(java.io.File(path!!).readText().trim())
        val http = okhttp3.OkHttpClient.Builder().callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        fun get(url: String): ByteArray? = runCatching {
            http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "VelaMaps-naming-study (github.com/PimpinPumpkin/Vela)").build())
                .execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
        }.getOrNull()
        val template = Regex("\"tiles\"\\s*:\\s*\\[\\s*\"([^\"]+)\"").find(String(get("https://tiles.openfreemap.org/planet")!!))!!.groupValues[1]
        val before = RoadNameTiles.fetch
        try {
            RoadNameTiles.clearCache()
            RoadNameTiles.fetch = { z, x, y -> get(template.replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")) }
            val roads = RoadNameTiles.roadsAlong(line, maxTiles = 48)!!
            RoadCenter.nudge(line, roads) // once to warm up, then timed
            val t0 = System.nanoTime()
            val (out, movedM) = RoadCenter.nudgeCounted(line, roads)
            val ms = (System.nanoTime() - t0) / 1e6
            fun pts(l: List<LatLng>) = l.joinToString(",", "[", "]") { "[${it.lat},${it.lng}]" }
            val near = roads.filter { r -> r.points.any { p -> line.any { q -> kotlin.math.abs(p.lat - q.lat) < 0.0006 && kotlin.math.abs(p.lng - q.lng) < 0.0008 } } }
            java.io.File("$path.json").writeText(
                "{\"line\":${pts(line)},\"nudged\":${pts(out)},\"roads\":[" + near.joinToString(",") { "{\"cls\":\"${it.cls}\",\"service\":\"${it.service}\",\"pts\":${pts(it.points)}}" } + "]}",
            )
            println("NUDGE ${line.size} points, $movedM m moved, ${roads.size} roads read, ${"%.1f".format(ms)} ms")
        } finally {
            RoadNameTiles.fetch = before
            RoadNameTiles.clearCache()
        }
    }
}
