package app.vela.core.data

import app.vela.core.model.LatLng
import app.vela.core.model.Route
import app.vela.core.model.RouteSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The bounded reroute's fallback order when the open router gave nothing (issue #557): Google's
 * route from the same fetch if it is already back, else the downloaded region, else nothing, and
 * never past the budget. Davis, CA fixtures.
 */
class RerouteFallbackTest {
    private val a = LatLng(38.5449, -121.7405)
    private val b = LatLng(38.5616, -121.7625)

    private fun route(source: RouteSource) = Route(listOf(a, b), emptyList(), 2_500.0, 300.0, null, source = source)

    @Test fun `google already back wins without starting the offline engine`() = runBlocking {
        val google = CompletableDeferred(listOf(route(RouteSource.GOOGLE_NAMED)))
        var offlineRan = false
        val out = RerouteFallback.pick(google, onDevice = { offlineRan = true; listOf(route(RouteSource.OBF)) }, budgetMs = 5_000)
        assertEquals(RerouteFallback.Source.GOOGLE_READY, out.source)
        assertFalse(offlineRan)
        assertFalse(out.onDeviceTried)
    }

    @Test fun `the downloaded region answers while google is still out`() = runBlocking {
        val google = CompletableDeferred<List<Route>>() // never completes
        val out = RerouteFallback.pick(google, onDevice = { listOf(route(RouteSource.OBF)) }, budgetMs = 5_000)
        assertEquals(RerouteFallback.Source.ON_DEVICE, out.source)
        assertEquals(RouteSource.OBF, out.routes.single().source)
        assertTrue(out.waitedMs < 5_000)
    }

    @Test fun `a slow offline compute does not hold back google`() = runBlocking {
        val google = CompletableDeferred<List<Route>>()
        val out = RerouteFallback.pick(
            google,
            onDevice = {
                google.complete(listOf(route(RouteSource.GOOGLE_NAMED)))
                Thread.sleep(6_000) // a native compute that ignores cancellation
                listOf(route(RouteSource.OBF))
            },
            budgetMs = 10_000,
        )
        assertEquals(RerouteFallback.Source.GOOGLE, out.source)
        // Well under the compute's own duration: the point is that Google's answer was not held
        // behind it. The margin is wide on purpose; a loaded CI runner failed a 2.5 s bound against
        // a 3 s sleep once (2026-09-22) with nothing wrong.
        assertTrue("waited ${out.waitedMs} ms for a compute it did not need", out.waitedMs < 4_500)
    }

    @Test fun `an empty google answer falls through to the region`() = runBlocking {
        val google = CompletableDeferred<List<Route>>(emptyList())
        val out = RerouteFallback.pick(google, onDevice = { listOf(route(RouteSource.OBF)) }, budgetMs = 5_000)
        assertEquals(RerouteFallback.Source.ON_DEVICE, out.source)
    }

    @Test fun `nothing answers inside the budget - fail, do not wait on`() = runBlocking {
        val google = CompletableDeferred<List<Route>>()
        val t0 = System.nanoTime()
        val out = RerouteFallback.pick(google, onDevice = { Thread.sleep(5_000); emptyList() }, budgetMs = 300)
        val tookMs = (System.nanoTime() - t0) / 1_000_000
        assertEquals(RerouteFallback.Source.NONE, out.source)
        assertTrue(out.routes.isEmpty())
        assertTrue("took $tookMs ms", tookMs < 2_000)
    }

    @Test fun `no region and no google - fails as soon as google says nothing`() = runBlocking {
        val google = CompletableDeferred<List<Route>>()
        val out = RerouteFallback.pick(google.also { it.complete(emptyList()) }, onDevice = null, budgetMs = 5_000)
        assertEquals(RerouteFallback.Source.NONE, out.source)
        assertTrue(out.waitedMs < 1_000)
    }

    @Test fun `both empty ends early`() = runBlocking {
        val google = CompletableDeferred<List<Route>>()
        val out = RerouteFallback.pick(google, onDevice = { google.complete(emptyList()); emptyList() }, budgetMs = 5_000)
        assertEquals(RerouteFallback.Source.NONE, out.source)
        assertTrue(out.waitedMs < 2_000)
    }

    // --- abandonableAsync ---------------------------------------------------------------
    // The phone-first reroute: the open router's call blocks in a socket read and ignores
    // cancellation, and the scope has to hand back the phone's route without it.

    @Test fun `a scope that returns early does not wait out a blocking call`() = runBlocking {
        val hung = CountDownLatch(1)
        try {
            val t0 = System.nanoTime()
            val (out, open) = coroutineScope {
                val openD = abandonableAsync { hung.await(8, TimeUnit.SECONDS); "open router" }
                val answer = withTimeoutOrNull(200) { openD.await() } ?: "phone"
                answer to openD
            }
            val tookMs = (System.nanoTime() - t0) / 1_000_000
            assertEquals("phone", out)
            // As a child of the scope the call held this for its whole 8 s. The bound is wide for a
            // loaded CI runner.
            assertTrue("took $tookMs ms", tookMs < 4_000)
            assertTrue("the call left behind was not canceled", open.isCancelled)
        } finally {
            hung.countDown()
        }
    }

    @Test fun `canceling the request cancels the call and does not wait for it`() = runBlocking {
        val hung = CountDownLatch(1)
        try {
            val started = CompletableDeferred<Deferred<String>>()
            val request = launch(Dispatchers.Default) {
                coroutineScope {
                    val openD = abandonableAsync { hung.await(8, TimeUnit.SECONDS); "open router" }
                    started.complete(openD)
                    openD.await()
                }
            }
            val open = started.await()
            val t0 = System.nanoTime()
            request.cancelAndJoin()
            val tookMs = (System.nanoTime() - t0) / 1_000_000
            assertTrue("took $tookMs ms", tookMs < 4_000)
            assertTrue("the call outlived its request uncanceled", open.isCancelled)
        } finally {
            hung.countDown()
        }
    }

    @Test fun `a call awaited to its end answers`() = runBlocking {
        val out = coroutineScope { abandonableAsync { Thread.sleep(50); "open router" }.await() }
        assertEquals("open router", out)
    }

    // --- RouteBudget --------------------------------------------------------------------

    @Test fun `an unbounded budget never limits an attempt`() {
        assertNull(RouteBudget.NONE.remainingMs())
        assertTrue(RouteBudget.canTry(null))
        assertNull(RouteBudget.tryTimeoutMs(null, null))
    }

    @Test fun `each attempt gets the smaller of its cap and what is left`() {
        assertEquals(6_000L, RouteBudget.tryTimeoutMs(6_000L, 20_000L))
        assertEquals(2_000L, RouteBudget.tryTimeoutMs(8_000L, 2_000L))
        assertEquals(9_000L, RouteBudget.tryTimeoutMs(null, 9_000L))
    }

    @Test fun `an attempt that cannot finish is not started`() {
        assertFalse(RouteBudget.canTry(RouteBudget.MIN_TRY_MS - 1))
        assertTrue(RouteBudget.canTry(RouteBudget.MIN_TRY_MS))
    }

    @Test fun `a budget counts down on its clock and a slice never outlives its parent`() {
        var now = 0L
        val clock = { now }
        val b = RouteBudget.of(10_000L, clock)
        now += 4_000L * 1_000_000L
        assertEquals(6_000L, b.remainingMs())
        assertEquals(4_000L, b.elapsedMs())
        assertEquals(6_000L, b.slice(8_000L).remainingMs())
        assertEquals(3_000L, b.slice(3_000L).remainingMs())
        now += 60_000L * 1_000_000L
        assertEquals(0L, b.remainingMs())
        assertFalse(RouteBudget.canTry(b.remainingMs()))
    }
}
