package app.vela.core.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Sharing several trips as one zip, and the list-row figures for a saved trip.
 *
 * Fixtures sit in the repo's standard Davis box (see the location-hygiene note in CLAUDE.md).
 */
class TripShareBatchTest {

    private val lat = 38.5449
    private val lng = -121.7405
    private val step = 0.00032 // ~28 m of longitude at this latitude

    private fun trip(n: Int): String = buildString {
        append("META,Somewhere,1756700000000,$lat,${lng + step * (n - 1)},2770\n")
        for (i in 0 until n) append("$lat,${lng + step * i},${1756700000000L + i * 1000},90,15,0,4.0\n")
    }

    @Test fun `a trip too short to trim is counted as left out, not sent`() {
        val long = TripScrub.scrub(trip(200))
        val short = TripScrub.scrub(trip(10))
        assertNull("a 250 m drive has no middle at a 400 m trim", short)
        val s = TripShareBatch.summarize(listOf(long, short))
        assertEquals(2, s.picked)
        assertEquals(1, s.kept)
        assertEquals(1, s.leftOut)
        assertEquals(long!!.fixesRemoved, s.fixesRemoved)
        assertEquals(long.fixesAfter, s.fixesKept)
    }

    @Test fun `trips started in the same minute get distinct entry names`() {
        val names = TripShareBatch.entryNames(listOf("2026-09-13-1432", "2026-09-13-1432", "2026-09-14-0800"))
        assertEquals(
            listOf("vela-trip-2026-09-13-1432.csv", "vela-trip-2026-09-13-1432-2.csv", "vela-trip-2026-09-14-0800.csv"),
            names,
        )
    }

    @Test fun `the zip holds exactly the given entries`() {
        val body = TripScrub.scrub(trip(200))!!.csv
        val out = ByteArrayOutputStream()
        TripShareBatch.writeZip(listOf("a.csv" to body, "b.csv" to "x"), out)
        val read = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                read[e.name] = zin.readBytes().toString(Charsets.UTF_8)
            }
        }
        assertEquals(listOf("a.csv", "b.csv"), read.keys.toList())
        assertEquals(body, read["a.csv"])
        assertTrue("the scrubbed body keeps no label", !read["a.csv"]!!.contains("Somewhere"))
    }

    @Test fun `redact starts the dialog on the widest trim`() {
        assertEquals(TripScrub.DEFAULT_RADIUS_M, TripScrub.defaultRadius(redact = false), 0.0)
        assertEquals(TripScrub.RADIUS_OPTIONS_M.max(), TripScrub.defaultRadius(redact = true), 0.0)
        assertTrue(TripScrub.DEFAULT_RADIUS_M in TripScrub.RADIUS_OPTIONS_M)
    }

    @Test fun `list stats add up distance and duration from the fixes only`() {
        val lines = trip(101).lineSequence() + sequenceOf("S,1756700050000,Turn left", "K,1756700060000,kept")
        val s = TripLog.stats(lines)
        assertEquals(101, s.fixes)
        assertEquals(100_000L, s.durationMs)
        // 100 steps of ~27.9 m
        assertEquals(2790.0, s.distanceM, 30.0)
    }

    @Test fun `an empty trip has zero stats`() {
        val s = TripLog.stats(sequenceOf("META,Nothing,0,,,1"))
        assertEquals(0, s.fixes)
        assertEquals(0.0, s.distanceM, 0.0)
        assertEquals(0L, s.durationMs)
    }
}
