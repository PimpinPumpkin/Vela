package app.vela.core.replay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A trip's nav trace: the row format and what a trimmed share keeps of it. */
class TripNavTraceTest {

    private val start = 1_756_700_000_000L

    private fun row(tMs: Long, p: Double, hold: Double = Double.NaN) =
        TripNavTrace.format(doubleArrayOf(tMs.toDouble(), p, 12.5, 40.0, 90.0, 90.5, 91.0, Double.NaN, 16.7, Double.NaN, hold))

    private fun rows(lines: List<String>) = lines.filterNot { it.startsWith("#") || it.startsWith("t_ms") || it.isBlank() }

    @Test fun `a row writes its columns in order and leaves NaN empty`() {
        assertEquals("1500,250.3,12.50,40,90.0,90.5,91.0,,16.7,,300.0", row(1500, 250.25, 300.0))
        assertEquals(TripNavTrace.WIDTH, row(0, 0.0).split(',').size)
        assertEquals(TripNavTrace.WIDTH, TripNavTrace.COLUMNS.split(',').size)
    }

    @Test fun `a trimmed share keeps only frames beside kept fixes, on the trip's rebased clock`() {
        // Fixes once a second for 60 s; the trim kept those from 20 s to 40 s.
        val kept = LongArray(21) { start + 20_000L + it * 1000L }
        val trace = buildString {
            append("# header\n").append(TripNavTrace.COLUMNS).append('\n')
            for (i in 0 until 600) append(row(i * 100L, i * 1.25)).append('\n') // 10 fps, 12.5 m/s
        }
        val out = TripNavTrace.trim(trace, start, kept)!!.lines()
        assertTrue(out[0] == "# header" && out[1] == TripNavTrace.COLUMNS)
        val r = rows(out).map { it.split(',') }
        assertEquals("the first kept frame is at the first kept fix", 0L, r.first()[0].toLong())
        assertEquals(20_000L, r.last()[0].toLong())
        assertEquals("meters count from the first kept frame", 0.0, r.first()[1].toDouble(), 1e-9)
        assertEquals(250.0, r.last()[1].toDouble(), 0.05)
    }

    @Test fun `frames inside a zone passed mid-drive go with its fixes`() {
        // Kept fixes 0..20 s and 40..60 s: the 20 s between are a Home zone on the way.
        val kept = (LongArray(21) { start + it * 1000L }.toList() + LongArray(21) { start + 40_000L + it * 1000L }.toList()).toLongArray()
        val trace = (0 until 600).joinToString("\n") { row(it * 100L, it * 1.25) }
        val t = rows(TripNavTrace.trim(trace, start, kept)!!.lines()).map { it.split(',')[0].toLong() }
        assertTrue("nothing deeper than the 3 s rule", t.none { it in 23_100L..36_900L })
        assertTrue(t.any { it in 20_000L..23_000L } && t.any { it in 37_000L..40_000L })
    }

    @Test fun `a reroute's meters count from its own first kept frame, and the hold moves with them`() {
        val kept = LongArray(41) { start + 10_000L + it * 1000L } // 10..50 s kept
        val trace = buildString {
            for (i in 0 until 300) append(row(i * 100L, 400.0 + i * 1.25, hold = 900.0)).append('\n') // first route
            for (i in 0 until 300) append(row(30_000L + i * 100L, 3.0 + i * 1.25, hold = 120.0)).append('\n') // rerouted at 30 s
        }
        val r = rows(TripNavTrace.trim(trace, start, kept)!!.lines()).map { it.split(',') }
        val first = r.first()
        assertEquals(0.0, first[1].toDouble(), 1e-9)
        assertEquals("the corner ahead keeps its distance from the car", 900.0 - (400.0 + 100 * 1.25), first[10].toDouble(), 0.05)
        val reroute = r.first { it[0].toLong() >= 20_000L }
        assertEquals("the new route starts again at zero, giving away no start distance", 0.0, reroute[1].toDouble(), 1e-9)
        assertEquals(117.0, reroute[10].toDouble(), 0.05)
    }

    @Test fun `no kept frame means no trace`() {
        val trace = (0 until 50).joinToString("\n") { row(it * 100L, it * 1.0) }
        assertNull(TripNavTrace.trim(trace, start, LongArray(5) { start + 60_000L + it * 1000L }))
        assertNull(TripNavTrace.trim(trace, start, LongArray(0)))
    }

    @Test fun `the scrub hands back the raw times of the fixes it kept`() {
        val lat = 38.5449
        val lng = -121.7405
        val csv = buildString {
            append("META,Somewhere,$start,$lat,${lng + 0.00032 * 199},2770\n")
            for (i in 0 until 200) append("$lat,${lng + 0.00032 * i},${start + i * 1000L},90,15,0,4.0\n")
        }
        val r = TripScrub.scrub(csv)!!
        assertEquals(r.fixesAfter, r.keptFixTimes.size)
        assertTrue(r.keptFixTimes.first() > start && r.keptFixTimes.last() < start + 199_000L)
    }
}
