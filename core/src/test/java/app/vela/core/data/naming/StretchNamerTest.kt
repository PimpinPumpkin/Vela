package app.vela.core.data.naming

import app.vela.core.data.naming.StretchNamer.Named
import app.vela.core.data.naming.StretchNamer.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections

class StretchNamerTest {
    private val a = HybridRoute.Stretch(0.0, 500.0)
    private val b = HybridRoute.Stretch(900.0, 1400.0)
    private val matched = Named(emptyList(), Source.MATCHED)
    private val withLanes = Named(emptyList(), Source.MATCHED, lanes = true)

    private val HEDGE = 150L
    private val DEADLINE = 600L
    private val NEVER = 5_000L

    /** Records each tile read as fetch or in-hand; after a fetch the tiles are "read". */
    private class Tiles(val takesMs: Long = 20) {
        val calls: MutableList<Boolean> = Collections.synchronizedList(ArrayList())
        @Volatile var read = false
        suspend fun get(fetch: Boolean): Named {
            calls += fetch
            if (fetch) { delay(takesMs); read = true }
            return Named(emptyList(), if (read) Source.TILES else Source.BARE)
        }
    }

    @Test fun `a quick match is used and no tile is asked for`() = runBlocking {
        val tiles = Tiles()
        val n = StretchNamer({ delay(20); matched }, { _, _ -> withLanes }, { _, f -> tiles.get(f) }, HEDGE)
        val r = n.name(a)!!
        assertEquals(Source.MATCHED, r.source)
        assertTrue(r.lanes)
        assertTrue("the tiles were not read", tiles.calls.isEmpty())
    }

    @Test fun `a match with no lane detail stands as it is`() = runBlocking {
        val n = StretchNamer({ matched }, { _, _ -> null }, { _, _ -> null }, HEDGE)
        val r = n.name(a)!!
        assertEquals(Source.MATCHED, r.source)
        assertFalse(r.lanes)
    }

    @Test fun `a slow match has the tiles read beside it and is still the one used`() = runBlocking {
        val tiles = Tiles()
        val n = StretchNamer({ delay(HEDGE * 3); matched }, { _, _ -> null }, { _, f -> tiles.get(f) }, HEDGE)
        assertEquals(Source.MATCHED, n.name(a)!!.source)
        assertEquals(listOf(true), tiles.calls.toList())
    }

    @Test fun `a failed match goes to the tiles at once, not after the wait`() = runBlocking {
        val tiles = Tiles()
        val n = StretchNamer({ null }, { _, _ -> null }, { _, f -> tiles.get(f) }, NEVER)
        val t0 = System.currentTimeMillis()
        assertEquals(Source.TILES, n.name(a)!!.source)
        assertTrue("did not sit out the hedge wait", System.currentTimeMillis() - t0 < NEVER / 2)
    }

    @Test fun `a match still out at the deadline leaves the tile names in hand`() = runBlocking {
        val tiles = Tiles()
        val n = StretchNamer({ delay(NEVER); matched }, { _, _ -> null }, { _, f -> tiles.get(f) }, HEDGE)
        assertNull(withTimeoutOrNull(DEADLINE) { n.name(a) })
        val r = n.inHand(a)!!
        assertEquals("named from the tiles the hedge read", Source.TILES, r.source)
        assertEquals("one fetch beside the match, then no request", listOf(true, false), tiles.calls.toList())
        assertEquals(Source.TILES, n.result(a)!!.source)
    }

    @Test fun `with no tile read in time the stretch goes out bare`() = runBlocking {
        val tiles = Tiles(takesMs = NEVER)
        val n = StretchNamer({ delay(NEVER); matched }, { _, _ -> null }, { _, f -> tiles.get(f) }, HEDGE)
        assertNull(withTimeoutOrNull(DEADLINE) { n.name(a) })
        assertEquals(Source.BARE, n.inHand(a)!!.source)
    }

    @Test fun `a match whose lanes are late keeps its names`() = runBlocking {
        val tiles = Tiles()
        val n = StretchNamer({ matched }, { _, _ -> delay(NEVER); withLanes }, { _, f -> tiles.get(f) }, HEDGE)
        assertNull(withTimeoutOrNull(DEADLINE) { n.name(a) })
        val r = n.inHand(a)!!
        assertEquals(Source.MATCHED, r.source)
        assertFalse(r.lanes)
        assertTrue("the tiles were not needed", tiles.calls.none { !it })
    }

    @Test fun `one slow stretch does not cost the other its match`() = runBlocking {
        val tiles = Tiles()
        val n = StretchNamer({ st -> if (st == b) delay(NEVER); matched }, { _, _ -> null }, { _, f -> tiles.get(f) }, HEDGE)
        assertNull(withTimeoutOrNull(DEADLINE) { coroutineScope { listOf(a, b).map { async { n.name(it) } }.awaitAll() } })
        assertEquals(Source.MATCHED, n.inHand(a)!!.source)
        assertEquals(Source.TILES, n.inHand(b)!!.source)
    }
}
