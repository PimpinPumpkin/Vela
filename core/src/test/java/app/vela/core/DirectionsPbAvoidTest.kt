package app.vela.core

import app.vela.core.data.google.DirectionsPb
import app.vela.core.model.LatLng
import app.vela.core.model.TravelMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The avoid flags live in the `!6m` feature block's `!2m` submessage (captured from Google's own
 *  web client and verified live 2026-09-06); the group counts must grow with them. */
class DirectionsPbAvoidTest {
    private val block = "!6m56!1m5!18b1!30b1!31m1!1b1!34e1!2m4!5m1!6e2"

    @Test fun `no flags leaves the template alone`() {
        assertEquals(DirectionsPb.DEFAULT_TEMPLATE, DirectionsPb.withAvoid(DirectionsPb.DEFAULT_TEMPLATE, false, false))
    }

    @Test fun `avoid highways adds 1b1 and bumps both counts`() {
        val out = DirectionsPb.withAvoid(DirectionsPb.DEFAULT_TEMPLATE, avoidTolls = false, avoidHighways = true)
        assertTrue(out.contains("!6m57!1m5!18b1!30b1!31m1!1b1!34e1!2m5!1b1!5m1!6e2"))
        assertTrue(!out.contains(block))
    }

    @Test fun `avoid tolls adds 2b1, both adds both`() {
        assertTrue(DirectionsPb.withAvoid(DirectionsPb.DEFAULT_TEMPLATE, true, false).contains("!6m57!1m5!18b1!30b1!31m1!1b1!34e1!2m5!2b1!5m1!6e2"))
        assertTrue(DirectionsPb.withAvoid(DirectionsPb.DEFAULT_TEMPLATE, true, true).contains("!6m58!1m5!18b1!30b1!31m1!1b1!34e1!2m6!1b1!2b1!5m1!6e2"))
    }

    @Test fun `flags only apply to driving`() {
        val walk = DirectionsPb.build(LatLng(38.5449, -121.7405), LatLng(38.5816, -121.4944), TravelMode.WALK, avoidTolls = true, avoidHighways = true)
        assertTrue(walk.contains(block))
        val drive = DirectionsPb.build(LatLng(38.5449, -121.7405), LatLng(38.5816, -121.4944), TravelMode.DRIVE, avoidTolls = true, avoidHighways = true)
        assertTrue(drive.contains("!2m6!1b1!2b1"))
    }

    @Test fun `a template without the block is returned untouched rather than corrupted`() {
        assertEquals("!1m1!2b1", DirectionsPb.withAvoid("!1m1!2b1", true, true))
    }

    @Test fun `avoidSupported tells a template with the block from one without`() {
        assertTrue(DirectionsPb.avoidSupported(DirectionsPb.DEFAULT_TEMPLATE))
        assertTrue(!DirectionsPb.avoidSupported("!1m1!2b1"))
    }
}

/** Avoid ferries is `!7b` directly under the same outer `!6m` block (captured from Google's web
 *  client 2026-09-16, issue #546): `!7b1` ticked, `!7b0` not. */
class DirectionsPbFerryTest {
    private val t = DirectionsPb.DEFAULT_TEMPLATE

    /** The outer block's own text, from `!6m<n>` through exactly the tokens its count covers. */
    private fun outerBlock(pb: String): String {
        val start = Regex("""!6m(\d+)!1m5!18b1""").find(pb)!!
        val count = start.groupValues[1].toInt()
        val tokens = Regex("""![^!]*""").findAll(pb, start.range.first).take(count + 1).toList()
        return pb.substring(start.range.first, tokens.last().range.last + 1)
    }

    @Test fun `default template has no ferry flag and its block ends where Google's does`() {
        assertTrue(!outerBlock(t).contains("!7b"))
        assertTrue(outerBlock(t).endsWith("!96b1!99b1"))
    }

    @Test fun `every combination on a template without 7b`() {
        for (tolls in listOf(false, true)) for (hw in listOf(false, true)) for (ferry in listOf(false, true)) {
            val out = DirectionsPb.withAvoid(t, tolls, hw, ferry)
            val flags = (if (hw) "!1b1" else "") + (if (tolls) "!2b1" else "")
            val n = flags.length / 4
            val outer = 56 + n + (if (ferry) 1 else 0)
            val label = "tolls=$tolls highways=$hw ferries=$ferry"
            assertTrue(label, out.contains("!6m$outer!1m5!18b1!30b1!31m1!1b1!34e1!2m${4 + n}$flags!5m1!6e2"))
            if (ferry) {
                assertTrue(label, out.contains("!291m0!7b1!10b1!12b1"))
                assertEquals(label, 1, Regex("!7b").findAll(out).count())
            } else {
                assertTrue(label, !out.contains("!7b"))
            }
            if (!tolls && !hw && !ferry) assertEquals(t, out)
            // The block still covers exactly its declared count and ends at the same field.
            assertTrue(label, outerBlock(out).endsWith("!96b1!99b1"))
            // Everything after the block is untouched.
            assertEquals(label, t.substringAfter("!99b1"), out.substringAfter("!99b1"))
        }
    }

    private val withSeven0 = t.replace("!6m56!", "!6m57!").replace("!291m0!10b1", "!291m0!7b0!10b1")
    private val withSeven1 = t.replace("!6m56!", "!6m57!").replace("!291m0!10b1", "!291m0!7b1!10b1")

    @Test fun `every combination on a template that already carries 7b`() {
        for (base in listOf(withSeven0, withSeven1)) {
            for (tolls in listOf(false, true)) for (hw in listOf(false, true)) for (ferry in listOf(false, true)) {
                val out = DirectionsPb.withAvoid(base, tolls, hw, ferry)
                val n = (if (hw) 1 else 0) + (if (tolls) 1 else 0)
                val label = "base7b=${base === withSeven1} tolls=$tolls highways=$hw ferries=$ferry"
                assertTrue(label, out.contains("!6m${57 + n}!1m5!18b1"))
                assertTrue(label, out.contains("!291m0!7b${if (ferry) 1 else 0}!10b1"))
                assertEquals(label, 1, Regex("!7b").findAll(out).count())
                assertTrue(label, outerBlock(out).endsWith("!96b1!99b1"))
            }
        }
        assertEquals(withSeven0, DirectionsPb.withAvoid(withSeven0, false, false, false))
        assertEquals(withSeven1, DirectionsPb.withAvoid(withSeven1, false, false, true))
    }

    @Test fun `a 7b nested deeper is not mistaken for the ferry flag`() {
        val nested = t.replace("!17m1!3e1", "!17m2!3e1!7b0").replace("!6m56!", "!6m57!")
        val out = DirectionsPb.withAvoid(nested, false, false, true)
        assertTrue(out.contains("!17m2!3e1!7b0"))
        assertTrue(out.contains("!291m0!7b1!10b1"))
        assertTrue(out.contains("!6m58!1m5"))
    }

    @Test fun `ferries only apply to driving`() {
        val a = LatLng(38.5449, -121.7405)
        val b = LatLng(38.5816, -121.4944)
        assertTrue(!DirectionsPb.build(a, b, TravelMode.WALK, avoidFerries = true).contains("!7b"))
        assertTrue(DirectionsPb.build(a, b, TravelMode.DRIVE, avoidFerries = true).contains("!291m0!7b1!10b1"))
    }

    @Test fun `a template without the block ignores the ferry flag`() {
        assertEquals("!1m1!2b1", DirectionsPb.withAvoid("!1m1!2b1", false, false, true))
    }
}
