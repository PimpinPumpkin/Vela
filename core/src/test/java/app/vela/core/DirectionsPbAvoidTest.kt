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
